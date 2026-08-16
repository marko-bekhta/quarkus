package io.quarkus.elasticsearch.restclient.vertx.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Schedules periodic node discovery using a {@link NodeDiscovery} and pushes the
 * discovered nodes to a consumer (typically the dispatcher's setNodes). Supports
 * on-failure re-discovery with a configurable delay.
 * <p>
 * Uses Vert.x timers instead of a dedicated {@code ScheduledExecutorService},
 * avoiding an extra thread. All timer management runs on the Vert.x event loop
 * via {@code runOnContext} to avoid races between timer creation and cancellation.
 */
public class NodeDiscoveryScheduler {

    private static final Logger LOG = Logger.getLogger(NodeDiscoveryScheduler.class);

    private final NodeDiscovery nodeDiscovery;
    private final Vertx vertx;
    private final Consumer<List<NodeImpl>> nodeUpdater;
    private final long discoveryIntervalMillis;
    private final long discoveryAfterFailureDelayMillis;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile boolean closed = false;

    // Mutated/read only inside runOnContext callbacks, but those may run on different
    // event-loop threads (the constructor thread, a dispatcher event loop via
    // discoverOnFailure, and the caller's close()); volatile guarantees visibility.
    private volatile long pendingTimerId = -1;

    NodeDiscoveryScheduler(NodeDiscovery nodeDiscovery, Vertx vertx, Consumer<List<NodeImpl>> nodeUpdater,
            long discoveryIntervalMillis, long discoveryAfterFailureDelayMillis) {
        this.nodeDiscovery = nodeDiscovery;
        this.vertx = vertx;
        this.nodeUpdater = nodeUpdater;
        this.discoveryIntervalMillis = discoveryIntervalMillis;
        this.discoveryAfterFailureDelayMillis = discoveryAfterFailureDelayMillis;
        vertx.runOnContext(v -> scheduleDiscovery(0, discoveryIntervalMillis));
    }

    public void discoverOnFailure() {
        if (!initialized.get()) {
            return;
        }
        vertx.runOnContext(v -> {
            if (closed) {
                return;
            }
            long current = pendingTimerId;
            if (current >= 0 && vertx.cancelTimer(current)) {
                scheduleDiscovery(0, discoveryAfterFailureDelayMillis);
            }
        });
    }

    /**
     * Stops scheduling and cancels any pending discovery timer. Following the Vert.x convention,
     * teardown is asynchronous: the returned future completes once the cancellation has run on the
     * event loop. The {@code closed} flag is set synchronously so no further discovery is scheduled
     * even before the returned future resolves.
     *
     * @return a future completed when the pending timer has been cancelled
     */
    public Future<Void> close() {
        closed = true;
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(v -> {
            long current = pendingTimerId;
            pendingTimerId = -1;
            if (current >= 0) {
                vertx.cancelTimer(current);
            }
            promise.complete();
        });
        return promise.future();
    }

    // Must be called on the event loop (via runOnContext or timer callback)
    private void scheduleDiscovery(long delayMillis, long nextIntervalMillis) {
        if (closed) {
            return;
        }
        pendingTimerId = vertx.setTimer(Math.max(1, delayMillis), id -> {
            if (closed) {
                return;
            }
            nodeDiscovery.discover()
                    .onSuccess(rawNodes -> {
                        if (rawNodes != null && !rawNodes.isEmpty()) {
                            List<NodeImpl> implNodes = new ArrayList<>(rawNodes.size());
                            for (Node node : rawNodes) {
                                implNodes.add(NodeImpl.from(node));
                            }
                            nodeUpdater.accept(implNodes);
                        } else {
                            LOG.warn("Node discovery returned empty node list, keeping existing nodes");
                        }
                    })
                    .onFailure(e -> LOG.error("Node discovery failed", e))
                    .eventually(() -> {
                        initialized.set(true);
                        vertx.runOnContext(v -> scheduleDiscovery(nextIntervalMillis, discoveryIntervalMillis));
                        return Future.succeededFuture();
                    });
        });
    }
}
