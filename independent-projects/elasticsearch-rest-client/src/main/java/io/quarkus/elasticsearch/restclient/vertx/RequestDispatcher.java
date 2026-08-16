package io.quarkus.elasticsearch.restclient.vertx;

import io.vertx.core.Future;

/**
 * Strategy interface for dispatching HTTP requests across a set of Elasticsearch nodes.
 * Implementations handle node selection, retry logic, and node list management, and own the
 * Vert.x {@code HttpClient} they send through -- created when the dispatcher is built and
 * released on {@link #close()}.
 */
public interface RequestDispatcher {

    CancellableFuture<Response> dispatch(Request request);

    /**
     * Closes this dispatcher, releasing the underlying Vert.x {@code HttpClient} and stopping any
     * node-discovery scheduling. Following the Vert.x convention (see {@code HttpClient#close()}),
     * teardown is asynchronous and the returned future completes when it finishes -- nothing blocks.
     * Callers that need to wait should join the future off the event loop, e.g.
     * {@code close().toCompletionStage().toCompletableFuture().get()}.
     *
     * @return a future completed when the dispatcher has fully closed
     */
    Future<Void> close();

    /**
     * Returns a factory that creates a round-robin dispatcher with dead-node tracking
     * and exponential backoff. Retryable server errors (502, 503, 504) cause automatic
     * failover to the next node.
     * <p>
     * Configure the returned factory with fluent setters before passing it to the
     * client builder:
     *
     * <pre>{@code
     * RequestDispatcher.roundRobin()
     *         .nodeSelector(NodeSelector.skipDedicatedMasters())
     *         .compressionEnabled(true)
     * }</pre>
     *
     * @return a new configurable {@link RequestDispatcherFactory}
     */
    static RequestDispatcherFactory roundRobin() {
        return new RoundRobinDispatcherFactory();
    }

    /**
     * Returns a factory that creates a dispatcher integrating with the Vert.x
     * {@code AddressResolver} and {@code LoadBalancer} SPIs for node resolution
     * and load balancing, while still maintaining dead-node tracking and retry behavior.
     * <p>
     * <strong>Experimental.</strong> This is the intended future default, but it is
     * currently opt-in: Vert.x does not surface the selected endpoint, so node identity
     * is recovered by matching the connection's remote address. Dead-node tracking is
     * therefore reliable only when nodes are configured by IP address -- with hostnames
     * that resolve to different IPs it silently stops working. Prefer {@link #roundRobin()}
     * unless you specifically need resolver-based dispatch. See the class-level TODO in
     * {@code ResolverRequestDispatcher} for the Vert.x SPI improvements being pursued.
     * <p>
     * Configure the returned factory with fluent setters before passing it to the
     * client builder:
     *
     * <pre>{@code
     * RequestDispatcher.vertxResolver()
     *         .failureListener(myListener)
     *         .defaultHeaders(Map.of("Authorization", "Basic ..."))
     * }</pre>
     *
     * @return a new configurable {@link RequestDispatcherFactory}
     */
    static RequestDispatcherFactory vertxResolver() {
        return new VertxResolverDispatcherFactory();
    }
}
