package io.quarkus.elasticsearch.javaclient.runtime;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import co.elastic.clients.transport.TransportOptions;
import co.elastic.clients.transport.http.TransportHttpClient;
import io.quarkus.elasticsearch.restclient.vertx.ResponseException;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;

/** Bridges the Java transport contract to the CDI-managed, pooled Vert.x client. */
final class VertxTransportHttpClient implements TransportHttpClient {

    private final VertxElasticsearchClient client;

    VertxTransportHttpClient(VertxElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Response performRequest(String endpointId, Node node, Request request, TransportOptions options) throws IOException {
        var translated = VertxTransportRequest.convert(node, request, options);
        try {
            return new VertxTransportResponse(client.performRequest(translated));
        } catch (ResponseException e) {
            // The Java transport decodes Elasticsearch error bodies, including HTTP 5xx responses.
            return new VertxTransportResponse(e.getResponse());
        }
    }

    @Override
    public CompletableFuture<Response> performRequestAsync(String endpointId, Node node, Request request,
            TransportOptions options) {
        try {
            var pending = client.performRequestAsync(VertxTransportRequest.convert(node, request, options));
            CompletableFuture<Response> result = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) {
                        pending.cancel();
                    }
                    return cancelled;
                }
            };
            pending.onComplete(outcome -> {
                if (outcome.succeeded()) {
                    result.complete(new VertxTransportResponse(outcome.result()));
                } else if (outcome.cause() instanceof ResponseException exception) {
                    result.complete(new VertxTransportResponse(exception.getResponse()));
                } else {
                    result.completeExceptionally(outcome.cause());
                }
            });
            return result;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        // The low-level client is shared with CDI consumers and is closed by its owning extension.
    }

}
