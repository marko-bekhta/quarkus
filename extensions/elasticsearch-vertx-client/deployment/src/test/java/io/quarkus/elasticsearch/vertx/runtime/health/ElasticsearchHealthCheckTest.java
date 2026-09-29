package io.quarkus.elasticsearch.vertx.runtime.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

class ElasticsearchHealthCheckTest {
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final ElasticsearchHealthCheck health = new ElasticsearchHealthCheck();
    private Vertx vertx;
    private HttpServer server;
    private VertxElasticsearchClient client;

    @BeforeEach
    void startServer() throws Exception {
        vertx = Vertx.vertx();
        server = get(vertx.createHttpServer().requestHandler(request -> {
            path.set(request.path());
            request.response().setStatusCode(status.get())
                    .putHeader("Content-Type", "application/json").end(body.get());
        }).listen(0, "127.0.0.1"));
        client = VertxElasticsearchClient.builder(vertx, URI.create("http://127.0.0.1:" + server.actualPort())).build();
        health.restClient = client;
    }

    @AfterEach
    void closeResources() throws Exception {
        if (client != null) {
            get(client.close());
        }
        if (server != null) {
            get(server.close());
        }
        if (vertx != null) {
            get(vertx.close());
        }
    }

    @Test
    void reportsGreenAndYellowAsUpAndRedAsDown() {
        for (String clusterStatus : new String[] { "green", "yellow", "red" }) {
            body.set("{\"status\":\"" + clusterStatus + "\"}");
            var response = health.call();
            assertThat(path.get()).isEqualTo("/_cluster/health");
            assertThat(response.getStatus()).isEqualTo("red".equals(clusterStatus)
                    ? HealthCheckResponse.Status.DOWN
                    : HealthCheckResponse.Status.UP);
            assertThat(response.getData()).hasValueSatisfying(data -> assertThat(data).containsEntry("status", clusterStatus));
        }
    }

    @Test
    void reportsServerErrorsAsDownWithReason() {
        status.set(503);
        body.set("{\"error\":\"unavailable\"}");
        var response = health.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).hasValueSatisfying(data -> assertThat(data).containsKey("reason"));
    }

    @Test
    void reportsMalformedResponsesAsDown() {
        body.set("not json");
        assertThat(health.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
