package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;

class VertxElasticsearchClientTest {

    static Vertx vertx;

    @BeforeAll
    static void setup() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void teardown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void discoveryStartsAfterClientConstruction(boolean resolver) throws Exception {
        CountDownLatch discovered = new CountDownLatch(1);
        VertxElasticsearchClient client = VertxElasticsearchClient.builder(vertx, URI.create("http://localhost:9200"))
                .setRequestDispatcher(resolver ? RequestDispatcher.vertxResolver() : RequestDispatcher.roundRobin())
                .nodeDiscovery(config -> config.nodeDiscoveryFactory(ignored -> () -> {
                    discovered.countDown();
                    return Future.succeededFuture(List.of());
                }))
                .build();
        try {
            assertThat(discovered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void discoveryRecoversWhenSelectorRejectsAllNodes(boolean resolver) throws Exception {
        AtomicReference<String> discoveryResponse = new AtomicReference<>();
        AtomicInteger discoveryHits = new AtomicInteger();
        AtomicInteger rejectedApplicationHits = new AtomicInteger();
        AtomicReference<String> discoveryAuthorization = new AtomicReference<>();
        HttpServer rejected = startServer(req -> {
            if (req.path().equals("/prefix/_nodes/http")) {
                discoveryHits.incrementAndGet();
                discoveryAuthorization.set(req.getHeader("Authorization"));
                req.response().end(discoveryResponse.get());
            } else {
                rejectedApplicationHits.incrementAndGet();
                req.response().end("rejected");
            }
        });
        HttpServer eligible = startServer(req -> {
            if (req.path().equals("/prefix/_nodes/http")) {
                req.response().end(discoveryResponse.get());
            } else {
                req.response().end("eligible");
            }
        });
        String master = nodeJson(rejected.actualPort(), "master");
        discoveryResponse.set("{\"nodes\":{\"master\":" + master + "}}");
        RequestDispatcherFactory factory = resolver ? RequestDispatcher.vertxResolver()
                : RequestDispatcher.roundRobin();
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://127.0.0.1:" + rejected.actualPort()))
                .setRequestDispatcher(factory.nodeSelector(NodeSelector.skipDedicatedMasters())
                        .pathPrefix("/prefix").defaultHeaders(Map.of("Authorization", "Bearer discovery-test")))
                .nodeDiscovery(config -> config.discoveryIntervalMillis(50))
                .build();
        try {
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThatThrownBy(() -> client.performRequest(new Request("GET", "/")))
                            .isInstanceOf(IOException.class).hasMessageContaining("No routable nodes"));
            int applicationHits = rejectedApplicationHits.get();
            int discoveries = discoveryHits.get();
            await().atMost(Duration.ofSeconds(5)).until(() -> discoveryHits.get() > discoveries);
            assertThatThrownBy(() -> client.performRequest(new Request("GET", "/_nodes/http")))
                    .isInstanceOf(IOException.class).hasMessageContaining("No routable nodes");
            assertThat(rejectedApplicationHits.get()).isEqualTo(applicationHits);
            assertThat(discoveryAuthorization.get()).isEqualTo("Bearer discovery-test");

            discoveryResponse.set("{\"nodes\":{\"master\":" + master + ",\"data\":"
                    + nodeJson(eligible.actualPort(), "data") + "}}");
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(client.performRequest(new Request("GET", "/")).getBody().toString())
                            .isEqualTo("eligible"));
            // Both policies now have cached resolver endpoints; discovery must never put the
            // rejected master back into application routing.
            for (int i = 0; i < 10; i++) {
                assertThat(client.performRequest(new Request("GET", "/")).getBody().toString()).isEqualTo("eligible");
            }
            assertThat(rejectedApplicationHits.get()).isEqualTo(applicationHits);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            rejected.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            eligible.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static String nodeJson(int port, String role) {
        return "{\"name\":\"node-" + port + "\",\"version\":\"9.0.0\",\"roles\":[\"" + role
                + "\"],\"http\":{\"publish_address\":\"127.0.0.1:" + port + "\"}}";
    }

    @Test
    void syncRequestWithEmbeddedServer() throws Exception {
        HttpServer server = startServer(req -> req.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end("{\"cluster_name\":\"test\"}"));
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            Response response = client.performRequest(new Request("GET", "/"));

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBody().toString()).contains("cluster_name");
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void asyncRequestWithEmbeddedServer() throws Exception {
        HttpServer server = startServer(req -> req.response().setStatusCode(200).end("{\"ok\":true}"));
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            Response response = client.performRequestAsync(new Request("GET", "/"))
                    .toCompletionStage().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(200);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void vertxNotShutDownOnClose() throws Exception {
        HttpServer server = startServer(req -> req.response().setStatusCode(200).end());
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            client.performRequest(new Request("GET", "/"));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        // Vertx should still be running after client.close()
        HttpServer server2 = startServer(req -> req.response().setStatusCode(200).end());
        assertThat(server2.actualPort()).isGreaterThan(0);
        server2.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void builderRequiresVertx() {
        assertThatThrownBy(() -> VertxElasticsearchClient.builder(null, URI.create("http://localhost:9200")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderRequiresAtLeastOneHost() {
        assertThatThrownBy(() -> VertxElasticsearchClient.builder(vertx, new URI[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsDuplicateHosts() {
        URI host = URI.create("http://localhost:9200");
        assertThatThrownBy(() -> VertxElasticsearchClient.builder(vertx, host, host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate host URIs are not allowed");
    }

    @Test
    void builderRejectsMixedSchemes() {
        URI http = URI.create("http://localhost:9200");
        URI https = URI.create("https://localhost:9200");
        assertThatThrownBy(() -> VertxElasticsearchClient.builder(vertx, http, https).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same URI scheme");
    }

    @Test
    void syncRequestThrowsResponseExceptionOn500() throws Exception {
        HttpServer server = startServer(req -> req.response().setStatusCode(500).end("error"));
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            assertThatThrownBy(() -> client.performRequest(new Request("GET", "/")))
                    .isInstanceOf(ResponseException.class);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void postWithBodyToEmbeddedServer() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = startServer(req -> req.body().onSuccess(buf -> {
            receivedBody.set(buf.toString());
            req.response().setStatusCode(200).end("{\"result\":\"created\"}");
        }));
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            Request request = new Request("PUT", "/test-index/_doc/1",
                    Map.of(), Map.of("Content-Type", "application/json"),
                    Buffer.buffer("{\"title\":\"hello\"}"), null);
            Response response = client.performRequest(request);

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(receivedBody.get()).isEqualTo("{\"title\":\"hello\"}");
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static HttpServer startServer(io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> handler)
            throws Exception {
        return vertx.createHttpServer()
                .requestHandler(handler)
                .listen(0)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
