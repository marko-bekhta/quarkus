package io.quarkus.elasticsearch.restclient.vertx;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;

import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/**
 * Main Elasticsearch REST client built on top of the Vert.x {@link HttpClient}. Delegates
 * request dispatch to a {@link RequestDispatcher} and supports both synchronous and
 * asynchronous request execution.
 * <p>
 * To trace HTTP requests at the wire level, enable Vert.x activity logging via
 * {@code HttpClientOptions.setLogActivity(true)} on the builder. This uses Netty's
 * pipeline logging and shows all requests, responses, headers, and bodies at DEBUG level.
 */
public class VertxElasticsearchClient {

    private final RequestDispatcher dispatcher;

    VertxElasticsearchClient(Vertx vertx, RequestDispatcherFactory factory,
            List<NodeImpl> initialNodes, NodeDiscoveryConfigurer nodeDiscoveryConfigurer, HttpConstants.Scheme scheme,
            HttpClientOptions httpClientOptions, PoolOptions poolOptions) {
        var context = new RequestDispatcherContext(this, initialNodes, nodeDiscoveryConfigurer, scheme, vertx,
                httpClientOptions, poolOptions);
        this.dispatcher = factory.create(context);
    }

    public static VertxElasticsearchClientBuilder builder(Vertx vertx, URI... hosts) {
        return new VertxElasticsearchClientBuilder(vertx, hosts);
    }

    public Response performRequest(Request request) throws IOException {
        Context context = Vertx.currentContext();
        if (context != null && context.isEventLoopContext()) {
            throw new IllegalStateException(
                    "Cannot call performRequest from a Vert.x event loop thread -- use performRequestAsync instead");
        }
        try {
            return performRequestAsync(request).toCompletionStage().toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("Request failed", cause);
        }
    }

    public CancellableFuture<Response> performRequestAsync(Request request) {
        return dispatcher.dispatch(request);
    }

    /**
     * Closes this client, releasing the underlying Vert.x {@code HttpClient} and stopping node
     * discovery. Following the Vert.x convention (see {@code HttpClient#close()}), teardown is
     * asynchronous: the returned future completes when it finishes and nothing blocks. Callers
     * that need to wait should join the future off the event loop, e.g.
     * {@code client.close().toCompletionStage().toCompletableFuture().get()}.
     *
     * @return a future completed when the client has fully closed
     */
    public Future<Void> close() {
        // The dispatcher owns the HTTP client and closes it as part of its own close().
        return dispatcher.close();
    }
}
