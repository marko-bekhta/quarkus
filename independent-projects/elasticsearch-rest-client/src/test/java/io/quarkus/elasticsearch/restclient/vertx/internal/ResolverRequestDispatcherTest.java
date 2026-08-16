package io.quarkus.elasticsearch.restclient.vertx.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.ResponseException;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;

class ResolverRequestDispatcherTest {

    static Vertx vertx;

    @BeforeAll
    static void setup() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void teardown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void successfulRequest() throws Exception {
        HttpServer server = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(200).end("{\"ok\":true}");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Response response = dispatch(dispatcher, new Request("GET", "/"));

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBody().toString()).isEqualTo("{\"ok\":true}");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void postWithBody() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = startServer(vertx, (req, resp) -> {
            req.body().onSuccess(buf -> {
                receivedBody.set(buf.toString());
                resp.setStatusCode(200).end("{\"ok\":true}");
            });
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Request request = new Request("POST", "/_search",
                    Map.of(), Map.of("Content-Type", "application/json"),
                    Buffer.buffer("{\"query\":{}}"), null);
            Response response = dispatch(dispatcher, request);

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(receivedBody.get()).isEqualTo("{\"query\":{}}");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void retryOn503() throws Exception {
        AtomicInteger server1Hits = new AtomicInteger();
        AtomicInteger server2Hits = new AtomicInteger();

        HttpServer server1 = startServer(vertx, (req, resp) -> {
            server1Hits.incrementAndGet();
            resp.setStatusCode(503).end();
        });
        HttpServer server2 = startServer(vertx, (req, resp) -> {
            server2Hits.incrementAndGet();
            resp.setStatusCode(200).end("{\"ok\":true}");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(
                    new NodeImpl(URI.create("http://localhost:" + server1.actualPort())),
                    new NodeImpl(URI.create("http://localhost:" + server2.actualPort()))));

            Response response = dispatch(dispatcher, new Request("GET", "/"));

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(server1Hits.get()).isEqualTo(1);
            assertThat(server2Hits.get()).isEqualTo(1);
        } finally {
            server1.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void noRetryOn500() throws Exception {
        HttpServer server = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(500).end("error");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            try {
                dispatch(dispatcher, new Request("GET", "/"));
                assertThat(false).as("Should have thrown").isTrue();
            } catch (Exception e) {
                Throwable cause = unwrap(e);
                assertThat(cause).isInstanceOf(ResponseException.class);
            }
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void response404ReturnedAsSuccess() throws Exception {
        HttpServer server = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(404).end("{\"error\":\"not_found\"}");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Response response = dispatch(dispatcher, new Request("GET", "/missing"));
            assertThat(response.getStatusCode()).isEqualTo(404);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void connectionRefusedRetry() throws Exception {
        HttpServer server = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(200).end("{\"ok\":true}");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(
                    new NodeImpl(URI.create("http://localhost:1")),
                    new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Response response = dispatch(dispatcher, new Request("GET", "/"));
            assertThat(response.getStatusCode()).isEqualTo(200);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void allNodesFail503() throws Exception {
        HttpServer server1 = startServer(vertx, (req, resp) -> resp.setStatusCode(503).end());
        HttpServer server2 = startServer(vertx, (req, resp) -> resp.setStatusCode(503).end());
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(
                    new NodeImpl(URI.create("http://localhost:" + server1.actualPort())),
                    new NodeImpl(URI.create("http://localhost:" + server2.actualPort()))));

            try {
                dispatch(dispatcher, new Request("GET", "/"));
                assertThat(false).as("Should have thrown IOException").isTrue();
            } catch (Exception e) {
                Throwable cause = unwrap(e);
                assertThat(cause).isInstanceOf(IOException.class);
                assertThat(cause.getSuppressed()).isNotEmpty();
            }
        } finally {
            server1.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void setNodesIncrementsVersion() {
        ResolverRequestDispatcher dispatcher = createDispatcher();
        dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://a:9200"))));
        dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://b:9200"))));
        assertThat(dispatcher.getNodes()).hasSize(1);
        assertThat(dispatcher.getNodes().get(0).getHost()).isEqualTo(URI.create("http://b:9200"));
    }

    @Test
    void setNodesClearsDeadState() throws Exception {
        AtomicInteger server1Hits = new AtomicInteger();
        HttpServer server1 = startServer(vertx, (req, resp) -> {
            int hit = server1Hits.incrementAndGet();
            if (hit == 1) {
                resp.setStatusCode(503).end();
            } else {
                resp.setStatusCode(200).end();
            }
        });
        HttpServer server2 = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(200).end();
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            List<NodeImpl> nodeList = List.of(
                    new NodeImpl(URI.create("http://localhost:" + server1.actualPort())),
                    new NodeImpl(URI.create("http://localhost:" + server2.actualPort())));
            dispatcher.setNodes(nodeList);

            dispatch(dispatcher, new Request("GET", "/"));

            // Reset -- clears dead state
            dispatcher.setNodes(nodeList);
            server1Hits.set(0);

            boolean server1Tried = false;
            for (int i = 0; i < 4; i++) {
                dispatch(dispatcher, new Request("GET", "/"));
                if (server1Hits.get() > 0) {
                    server1Tried = true;
                    break;
                }
            }
            assertThat(server1Tried).isTrue();
        } finally {
            server1.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pathPrefixApplied() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer server = startServer(vertx, (req, resp) -> {
            receivedPath.set(req.path());
            resp.setStatusCode(200).end();
        });
        try {
            ResolverRequestDispatcher dispatcher = new ResolverRequestDispatcher(
                    NodeSelector.any(), FailureListener.NO_OP,
                    Map.of(), "/prefix", false, WarningsHandler.PERMISSIVE,
                    vertx);
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            dispatch(dispatcher, new Request("GET", "/_search"));

            assertThat(receivedPath.get()).isEqualTo("/prefix/_search");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestHeadersOverrideDefaults() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = startServer(vertx, (req, resp) -> {
            receivedAuth.set(req.getHeader("Authorization"));
            resp.setStatusCode(200).end();
        });
        try {
            ResolverRequestDispatcher dispatcher = new ResolverRequestDispatcher(
                    NodeSelector.any(), FailureListener.NO_OP,
                    Map.of("Authorization", "Basic default"),
                    null, false, WarningsHandler.PERMISSIVE,
                    vertx);
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Request request = new Request("GET", "/", Map.of(),
                    Map.of("Authorization", "Basic override"), null, null);
            dispatch(dispatcher, request);

            assertThat(receivedAuth.get()).isEqualTo("Basic override");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void compressionHeaderSent() throws Exception {
        AtomicReference<String> receivedAcceptEncoding = new AtomicReference<>();
        HttpServer server = startServer(vertx, (req, resp) -> {
            receivedAcceptEncoding.set(req.getHeader("Accept-Encoding"));
            resp.setStatusCode(200).end();
        });
        try {
            ResolverRequestDispatcher dispatcher = new ResolverRequestDispatcher(
                    NodeSelector.any(), FailureListener.NO_OP,
                    Map.of(), null, true, WarningsHandler.PERMISSIVE,
                    vertx);
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            dispatch(dispatcher, new Request("GET", "/"));

            assertThat(receivedAcceptEncoding.get()).isEqualTo("gzip");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void queryParametersUrlEncoded() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        HttpServer server = startServer(vertx, (req, resp) -> {
            receivedQuery.set(req.query());
            resp.setStatusCode(200).end();
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort()))));

            Request request = new Request("GET", "/_search",
                    Map.of("q", "hello world", "size", "10"),
                    Map.of(), null, null);
            dispatch(dispatcher, request);

            assertThat(receivedQuery.get()).contains("q=hello+world");
            assertThat(receivedQuery.get()).contains("size=10");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failureListenerCalledOnRetryableFailure() throws Exception {
        AtomicInteger failureCount = new AtomicInteger();
        HttpServer server1 = startServer(vertx, (req, resp) -> resp.setStatusCode(503).end());
        HttpServer server2 = startServer(vertx, (req, resp) -> resp.setStatusCode(200).end());
        try {
            ResolverRequestDispatcher dispatcher = new ResolverRequestDispatcher(
                    NodeSelector.any(), node -> failureCount.incrementAndGet(),
                    Map.of(), null, false, WarningsHandler.PERMISSIVE,
                    vertx);
            dispatcher.setNodes(List.of(
                    new NodeImpl(URI.create("http://localhost:" + server1.actualPort())),
                    new NodeImpl(URI.create("http://localhost:" + server2.actualPort()))));

            dispatch(dispatcher, new Request("GET", "/"));

            assertThat(failureCount.get()).isEqualTo(1);
        } finally {
            server1.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nodeMarkedDeadAfterFailure() throws Exception {
        AtomicInteger server1Hits = new AtomicInteger();
        HttpServer server1 = startServer(vertx, (req, resp) -> {
            server1Hits.incrementAndGet();
            resp.setStatusCode(503).end();
        });
        HttpServer server2 = startServer(vertx, (req, resp) -> {
            resp.setStatusCode(200).end("{\"ok\":true}");
        });
        try {
            ResolverRequestDispatcher dispatcher = createDispatcher();
            dispatcher.setNodes(List.of(
                    new NodeImpl(URI.create("http://localhost:" + server1.actualPort())),
                    new NodeImpl(URI.create("http://localhost:" + server2.actualPort()))));

            dispatch(dispatcher, new Request("GET", "/"));
            server1Hits.set(0);

            // Second request should skip the dead node
            dispatch(dispatcher, new Request("GET", "/"));
            assertThat(server1Hits.get()).isEqualTo(0);
        } finally {
            server1.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void addressResolverAndLoadBalancerFactories() {
        ResolverRequestDispatcher dispatcher = createDispatcher();
        dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://a:9200"))));

        assertThat(dispatcher.addressResolver()).isNotNull();
        assertThat(dispatcher.loadBalancer()).isNotNull();
    }

    // --- Helpers ---

    private static Response dispatch(ResolverRequestDispatcher dispatcher, Request request) throws Exception {
        CancellableFuture<Response> future = dispatcher.dispatch(request);
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static ResolverRequestDispatcher createDispatcher() {
        return new ResolverRequestDispatcher(
                NodeSelector.any(), FailureListener.NO_OP,
                Map.of(), null, false, WarningsHandler.PERMISSIVE,
                vertx);
    }

    private static Throwable unwrap(Throwable e) {
        if (e instanceof ExecutionException && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    @FunctionalInterface
    interface RequestHandler {
        void handle(io.vertx.core.http.HttpServerRequest req, io.vertx.core.http.HttpServerResponse resp);
    }

    private static HttpServer startServer(Vertx vertx, RequestHandler handler) throws Exception {
        return vertx.createHttpServer()
                .requestHandler(req -> handler.handle(req, req.response()))
                .listen(0)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
