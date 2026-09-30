package io.quarkus.hibernate.search.backend.elasticsearch.common.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.search.backend.elasticsearch.client.common.spi.ElasticsearchRequest;
import org.hibernate.search.engine.common.execution.spi.DelegatingSimpleScheduledExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

class VertxElasticsearchClientFactoryTest {

    private final Vertx vertx = Vertx.vertx();
    private HttpServer server;
    private VertxElasticsearchClientFactory.Client client;

    @AfterEach
    void close() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void bulkRequestAndErrorResponse() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        server = vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            received.set(request.method() + " " + request.uri() + " " + request.getHeader("Content-Type") + "\n"
                    + body.toString());
            request.response().setStatusCode(404).putHeader("Content-Type", "application/json; charset=utf-8")
                    .end("{\"error\":\"missing\"}");
        })).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client = newClient();

        JsonObject first = new JsonObject();
        first.addProperty("index", "books");
        JsonObject second = new JsonObject();
        second.addProperty("title", "A book");
        var request = ElasticsearchRequest.post().wholeEncodedPath("/_bulk").param("refresh", "true")
                .body(first).body(second).build();
        var response = client.submit(request).get(10, TimeUnit.SECONDS);

        assertThat(received.get()).isEqualTo("POST /_bulk?refresh=true application/json\n"
                + "{\"index\":\"books\"}\n{\"title\":\"A book\"}\n");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body().get("error").getAsString()).isEqualTo("missing");
        assertThat(response.hostAndPort()).contains(":" + server.actualPort());
        assertThat(client.unwrap(VertxElasticsearchClient.class)).isNotNull();
        assertThatThrownBy(() -> client.unwrap(String.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancellationStopsPendingRequest() throws Exception {
        server = vertx.createHttpServer().requestHandler(request -> {
            // Keep the exchange open until the client cancels it.
        }).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client = newClient();

        var response = client.submit(ElasticsearchRequest.get().wholeEncodedPath("/slow").build());
        assertThat(response.cancel(true)).isTrue();
        assertThatThrownBy(response::join).isInstanceOf(CancellationException.class);
    }

    @Test
    void cancellationClosesUnfinishedResponseBody() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        server = vertx.createHttpServer().requestHandler(request -> {
            request.connection().closeHandler(ignored -> disconnected.countDown());
            request.response().setChunked(true).write("{").onSuccess(ignored -> headersSent.countDown());
        }).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client = newClient();

        var response = client.submit(ElasticsearchRequest.get().wholeEncodedPath("/stream").build());
        assertThat(headersSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(response.cancel(true)).isTrue();
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void closingOneBackendDoesNotCloseSharedVertxOrAnotherBackend() throws Exception {
        server = vertx.createHttpServer().requestHandler(request -> request.response().end("{}"))
                .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client = newClient();
        VertxElasticsearchClientFactory.Client other = newClient();
        try {
            client.close();
            client = null;
            assertThat(other.submit(ElasticsearchRequest.get().wholeEncodedPath("/").build())
                    .get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            other.close();
        }
    }

    @Test
    void requestTimeoutCompletesFuture() throws Exception {
        server = vertx.createHttpServer().requestHandler(request -> {
            // Keep the exchange open until its Search request timeout expires.
        }).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            URI host = URI.create("http://localhost:" + server.actualPort());
            client = new VertxElasticsearchClientFactory.Client(vertx,
                    VertxElasticsearchClient.builder(vertx, host).build(),
                    new Gson(), new DelegatingSimpleScheduledExecutor(scheduler, false), Optional.of(50));
            assertThatThrownBy(() -> client.submit(ElasticsearchRequest.get().wholeEncodedPath("/slow").build())
                    .get(5, TimeUnit.SECONDS)).hasMessageContaining("exceeded the timeout");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private VertxElasticsearchClientFactory.Client newClient() {
        URI host = URI.create("http://localhost:" + server.actualPort());
        return new VertxElasticsearchClientFactory.Client(vertx, VertxElasticsearchClient.builder(vertx, host).build(),
                new Gson(), null, Optional.empty());
    }
}
