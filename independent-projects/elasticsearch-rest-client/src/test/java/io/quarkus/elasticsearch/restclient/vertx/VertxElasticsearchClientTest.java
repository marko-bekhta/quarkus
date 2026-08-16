package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
