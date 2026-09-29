package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/**
 * Default {@link io.quarkus.elasticsearch.restclient.vertx.RequestDispatcher} implementation
 * that uses round-robin node selection with dead-node tracking and exponential backoff.
 * Retryable server errors (502, 503, 504) cause automatic failover to the next available node.
 */
public class DefaultRequestDispatcher extends AbstractRequestDispatcher {

    /**
     * Monotonic counter driving round-robin: each request reads and increments it, and the
     * value modulo the routable-node count picks the starting node. It is decoupled from the
     * node list itself (rather than being an index into a specific list instance), so swapping
     * the routable snapshot on discovery never invalidates it.
     */
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public DefaultRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            List<NodeImpl> initialNodes, NodeDiscoveryConfigurer nodeDiscoveryConfigurer,
            VertxElasticsearchClient client, HttpConstants.Scheme scheme,
            Vertx vertx, HttpClientOptions httpClientOptions, PoolOptions poolOptions,
            BackoffStrategy backoffStrategy, Supplier<Long> nanoTimeSupplier) {
        super(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler,
                initialNodes, nodeDiscoveryConfigurer, client, scheme, vertx,
                httpClientOptions, poolOptions, backoffStrategy, nanoTimeSupplier);
    }

    @Override
    protected HttpClient createHttpClient(Vertx vertx, HttpClientOptions options, PoolOptions poolOptions) {
        HttpClientOptions opts = withTransportDefaults(options);
        if (poolOptions != null) {
            return vertx.createHttpClient(opts, poolOptions);
        }
        return vertx.createHttpClient(opts);
    }

    @Override
    public CancellableFuture<Response> dispatch(Request request) {
        return dispatch(request, nodeSnapshot.routableNodes());
    }

    @Override
    CancellableFuture<Response> dispatchForDiscovery(Request request) {
        return dispatch(request, nodeSnapshot.allNodes());
    }

    private CancellableFuture<Response> dispatch(Request request, List<NodeImpl> nodes) {
        Promise<Response> promise = Promise.promise();
        Context context = vertx.getOrCreateContext();
        CancellableFuture<Response> cancellable = new CancellableFuture<>(promise.future(), context);

        // HttpClient captures the current context for acquisition, response and retry callbacks.
        context.runOnContext(ignored -> {
            try {
                Iterator<NodeImpl> nodeIterator = selectNodes(nodes).iterator();
                tryNode(request, nodeIterator, null, cancellable).onComplete(promise);
            } catch (Exception e) {
                promise.tryFail(e);
            }
        });
        return cancellable;
    }

    /**
     * Chooses the order in which nodes are tried for a single request.
     * <p>
     * Node-selector filtering has already been applied when the node set was published (see
     * {@link AbstractRequestDispatcher#computeRoutableNodes(List)}), so this method works purely
     * on liveness -- no selection happens here. It reads the immutable routable node snapshot
     * snapshot once and:
     * <ol>
     * <li>picks a starting offset from {@link #roundRobinCounter} so consecutive requests
     * fan out across nodes;</li>
     * <li>walks the ring from that offset, collecting every node whose dead-node backoff has
     * elapsed ({@link NodeImpl#shouldBeRetried(long)}) into a live list -- already in the order
     * they should be tried;</li>
     * <li>if no node is currently live, falls back to the single node whose backoff expires
     * soonest (the "least dead"), giving the request one node to attempt rather than none.</li>
     * </ol>
     * The returned list is what {@link #tryNode} iterates, attempting the next node on a
     * retryable failure.
     */
    List<NodeImpl> selectNodes() throws IOException {
        return selectNodes(nodeSnapshot.routableNodes());
    }

    private List<NodeImpl> selectNodes(List<NodeImpl> routable) throws IOException {
        if (routable.isEmpty()) {
            throw noRoutableNodesException();
        }

        int size = routable.size();
        if (size == 1) {
            // Single node: nothing to rotate or compare, liveness is handled at send time.
            return routable;
        }

        long nowNanos = nowNanos();
        int start = Math.floorMod(roundRobinCounter.getAndIncrement(), size);

        List<NodeImpl> live = null;
        NodeImpl leastDead = null;
        long leastDeadUntilNanos = Long.MAX_VALUE;

        for (int i = 0; i < size; i++) {
            NodeImpl node = routable.get((start + i) % size);
            if (node.shouldBeRetried(nowNanos)) {
                if (live == null) {
                    live = new ArrayList<>(size);
                }
                live.add(node);
            } else {
                DeadHostState state = node.deadState.get();
                long deadUntilNanos = state != null ? state.getDeadUntilNanos() : Long.MIN_VALUE;
                if (deadUntilNanos < leastDeadUntilNanos) {
                    leastDead = node;
                    leastDeadUntilNanos = deadUntilNanos;
                }
            }
        }

        return live != null ? live : List.of(leastDead);
    }

    private Future<Response> tryNode(Request request,
            Iterator<NodeImpl> nodeIterator, Throwable previousException,
            CancellableFuture<Response> cancellable) {
        if (!nodeIterator.hasNext()) {
            return Future.failedFuture(previousException != null
                    ? previousException
                    : new IOException("No nodes available"));
        }

        NodeImpl node = nodeIterator.next();
        return sendToNode(request, node, previousException,
                ex -> tryNode(request, nodeIterator, ex, cancellable),
                cancellable);
    }
}
