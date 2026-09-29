package io.quarkus.elasticsearch.javaclient.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.DefaultTransportOptions;
import co.elastic.clients.transport.TransportException;
import co.elastic.clients.transport.http.TransportHttpClient;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.WarningFailureException;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

class VertxTransportHttpClientTest {

    private Vertx vertx;
    private HttpServer server;
    private VertxElasticsearchClient client;
    private VertxTransportHttpClient adapter;
    private final AtomicReference<Handler<HttpServerRequest>> handler = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        handler.set(request -> request.response().end("{}"));
        server = vertx.createHttpServer().requestHandler(request -> {
            request.response().putHeader("X-Elastic-Product", "Elasticsearch");
            request.response().putHeader("Content-Type", "application/json");
            handler.get().handle(request);
        }).listen(0, "localhost").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client = VertxElasticsearchClient.builder(vertx, URI.create("http://localhost:" + server.actualPort())).build();
        adapter = new VertxTransportHttpClient(client);
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void translatesBodyAndOptionsWithoutConsumingBuffers() throws Exception {
        CompletableFuture<HttpServerRequest> received = new CompletableFuture<>();
        CompletableFuture<String> body = new CompletableFuture<>();
        handler.set(request -> request.body().onSuccess(buffer -> {
            received.complete(request);
            body.complete(buffer.toString());
            request.response().end("{}");
        }));
        ByteBuffer part = ByteBuffer.wrap("x{\"index\":{}}\n".getBytes(StandardCharsets.UTF_8));
        part.position(1);
        var request = new TransportHttpClient.Request("POST", "/_bulk", Map.of("refresh", "false"),
                Map.of("Content-Type", "application/x-ndjson", "X-Custom", "request"),
                List.of(part, ByteBuffer.wrap("{\"field\":1}\n".getBytes(StandardCharsets.UTF_8))));
        var options = new DefaultTransportOptions() {
            @Override
            public Collection<Map.Entry<String, String>> headers() {
                return List.of(Map.entry("x-custom", "one"), Map.entry("X-Custom", "two"));
            }

            @Override
            public Map<String, String> queryParameters() {
                return Map.of("refresh", "true");
            }
        };
        adapter.performRequest("bulk", null, request, options).close();
        var actual = received.get(10, TimeUnit.SECONDS);
        assertThat(actual.method().name()).isEqualTo("POST");
        assertThat(actual.path()).isEqualTo("/_bulk");
        assertThat(actual.getParam("refresh")).isEqualTo("true");
        assertThat(actual.headers().getAll("X-CUSTOM")).containsExactly("one", "two");
        assertThat(actual.getHeader("content-type")).isEqualTo("application/x-ndjson");
        assertThat(body.get(10, TimeUnit.SECONDS)).isEqualTo("{\"index\":{}}\n{\"field\":1}\n");
        assertThat(part.position()).isEqualTo(1);
    }

    @Test
    void responseRemainsRepeatableAfterClose() throws Exception {
        handler.set(request -> request.response().putHeader("X-Values", List.<String> of("one", "two")).end("{\"ok\":true}"));
        var response = adapter.performRequest("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY);
        response.close();
        response.close();
        assertThat(response.header("CONTENT-TYPE")).isEqualTo("application/json");
        assertThat(response.headers("x-values")).containsExactly("one", "two");
        assertThat(response.body().isRepeatable()).isTrue();
        assertThat(response.body().asInputStream().readAllBytes()).isEqualTo("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        assertThat(StandardCharsets.UTF_8.decode(response.body().asByteBuffer()).toString()).isEqualTo("{\"ok\":true}");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.body().writeTo(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(response.originalResponse()).isNotNull();
        assertThat(response.node().uri().getPort()).isEqualTo(server.actualPort());
    }

    @Test
    void emptyBodyAndHead404() throws Exception {
        handler.set(request -> request.response().setStatusCode(404).end());
        assertThat(adapter.performRequest("test", null, request("HEAD", "/missing"), DefaultTransportOptions.EMPTY).body())
                .isNull();
        var transport = new VertxElasticsearchTransport(client, new Jackson3JsonpMapper());
        assertThat(new ElasticsearchClient(transport).indices().exists(b -> b.index("missing")).value()).isFalse();
        assertThat(new ElasticsearchAsyncClient(transport).indices().exists(b -> b.index("missing"))
                .get(10, TimeUnit.SECONDS).value()).isFalse();
    }

    @Test
    void errorsAreDecodedByJavaTransport() throws Exception {
        handler.set(request -> request.response().setStatusCode(500)
                .end("{\"error\":{\"type\":\"test_error\",\"reason\":\"broken\"},\"status\":500}"));
        var typed = new ElasticsearchClient(new VertxElasticsearchTransport(client, new Jackson3JsonpMapper()));
        assertThatThrownBy(typed::info)
                .isInstanceOfSatisfying(ElasticsearchException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(500);
                    assertThat(exception.error().reason()).isEqualTo("broken");
                });
        assertThat(adapter.performRequestAsync("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY)
                .get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(500);
    }

    @Test
    void retryableErrorRetainsResponse() throws Exception {
        handler.set(request -> request.response().setStatusCode(503)
                .end("{\"error\":{\"type\":\"unavailable\",\"reason\":\"busy\"},\"status\":503}"));
        var response = adapter.performRequest("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body().asInputStream().readAllBytes()).asString().contains("busy");
    }

    @Test
    void parsingErrorBodyCanBeInspectedAfterTransportClosesResponse() {
        handler.set(request -> request.response().end("{broken"));
        var typed = new ElasticsearchClient(new VertxElasticsearchTransport(client, new Jackson3JsonpMapper()));
        assertThatThrownBy(typed::info).isInstanceOfSatisfying(TransportException.class, exception -> {
            try {
                assertThat(exception.response().body().asInputStream().readAllBytes()).asString().isEqualTo("{broken");
                assertThat(exception.response().body().asInputStream().readAllBytes()).asString().isEqualTo("{broken");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void warningHandlerCanRejectResponse() {
        handler.set(request -> request.response().putHeader("Warning", "299 Elasticsearch \"deprecated\"").end("{}"));
        var options = DefaultTransportOptions.EMPTY.toBuilder().onWarnings(warnings -> warnings.contains("deprecated")).build();
        assertThatThrownBy(() -> adapter.performRequest("test", null, request("GET", "/"), options))
                .isInstanceOf(WarningFailureException.class);
    }

    @Test
    void blockingCallsAreRejectedOnEventLoop() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            try {
                adapter.performRequest("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY);
                failure.complete(null);
            } catch (Throwable e) {
                failure.complete(e);
            }
        });
        assertThat(failure.get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingTypedCallClosesInFlightRequest() throws Exception {
        CompletableFuture<Void> received = new CompletableFuture<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        handler.set(request -> {
            request.connection().closeHandler(ignored -> closed.complete(null));
            received.complete(null);
        });
        var typed = new ElasticsearchAsyncClient(new VertxElasticsearchTransport(client, new Jackson3JsonpMapper()));
        var pending = typed.info();
        received.get(10, TimeUnit.SECONDS);
        assertThat(pending.cancel(true)).isTrue();
        closed.get(10, TimeUnit.SECONDS);
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void closingTransportPreservesSharedClient() throws Exception {
        new VertxElasticsearchTransport(client, new Jackson3JsonpMapper()).close();
        assertThat(client.performRequest(new Request("GET", "/")).getStatusCode()).isEqualTo(200);
    }

    @Test
    void networkFailuresRemainFailures() throws Exception {
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> adapter.performRequest("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> adapter.performRequestAsync("test", null, request("GET", "/"), DefaultTransportOptions.EMPTY)
                .get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
    }

    private static TransportHttpClient.Request request(String method, String path) {
        return new TransportHttpClient.Request(method, path, Map.of(), Map.of(), null);
    }
}
