package io.quarkus.elasticsearch.restclient.vertx.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class NodeDiscoverySchedulerTest {

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
    void initialDiscoveryRunsImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger discoveryCount = new AtomicInteger();
        List<NodeImpl> received = new ArrayList<>();

        NodeDiscovery mockDiscovery = () -> {
            discoveryCount.incrementAndGet();
            latch.countDown();
            return Future.succeededFuture(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
        };

        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(mockDiscovery, vertx, received::addAll,
                60_000, 1_000);
        scheduler.start();
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveryCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void constructionDoesNotStartDiscovery() throws Exception {
        CountDownLatch discovered = new CountDownLatch(1);
        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(() -> {
            discovered.countDown();
            return Future.succeededFuture(List.of());
        }, vertx, nodes -> {
        }, 60_000, 1_000);
        try {
            assertThat(discovered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            scheduler.start();
            assertThat(discovered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void synchronousDiscoveryExceptionDoesNotStopScheduler() throws Exception {
        AtomicInteger discoveryCount = new AtomicInteger();
        CountDownLatch updated = new CountDownLatch(1);
        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(() -> {
            if (discoveryCount.incrementAndGet() == 1) {
                throw new IllegalStateException("Synchronous discovery failure");
            }
            return Future.succeededFuture(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
        }, vertx, nodes -> updated.countDown(), 50, 1_000);
        try {
            scheduler.start();
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveryCount.get()).isGreaterThanOrEqualTo(2);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void foreignDiscoveryCompletionUpdatesNodesOnOwningContext() throws Exception {
        Context owner = vertx.getOrCreateContext();
        Promise<NodeDiscoveryScheduler> constructed = Promise.promise();
        Promise<List<Node>> result = Promise.promise();
        Promise<Context> updated = Promise.promise();
        Promise<Context> invoked = Promise.promise();
        owner.runOnContext(ignored -> constructed.complete(new NodeDiscoveryScheduler(() -> {
            invoked.tryComplete(Vertx.currentContext());
            return result.future();
        }, vertx, nodes -> updated.tryComplete(Vertx.currentContext()), 60_000, 1_000)));
        NodeDiscoveryScheduler scheduler = constructed.future().toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        try {
            // Start and complete discovery from the test thread, outside the scheduler context.
            scheduler.start();
            assertThat(invoked.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isSameAs(owner);
            result.complete(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
            assertThat(updated.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isSameAs(owner);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void foreignFailureTriggerKeepsDiscoveryOnOwningContext() throws Exception {
        Context owner = vertx.getOrCreateContext();
        Promise<NodeDiscoveryScheduler> constructed = Promise.promise();
        AtomicInteger discoveries = new AtomicInteger();
        AtomicReference<Context> secondContext = new AtomicReference<>();
        CountDownLatch secondDiscovery = new CountDownLatch(1);
        owner.runOnContext(ignored -> constructed.complete(new NodeDiscoveryScheduler(() -> {
            if (discoveries.incrementAndGet() == 2) {
                secondContext.set(Vertx.currentContext());
                secondDiscovery.countDown();
            }
            return Future.succeededFuture(List.of());
        }, vertx, nodes -> {
        }, 60_000, 60_000)));
        NodeDiscoveryScheduler scheduler = constructed.future().toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        try {
            scheduler.start();
            // Repeat until the first discovery has finished and failure-triggered discovery is enabled.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (secondDiscovery.getCount() != 0 && System.nanoTime() < deadline) {
                scheduler.discoverOnFailure();
                secondDiscovery.await(10, TimeUnit.MILLISECONDS);
            }
            assertThat(secondDiscovery.getCount()).isZero();
            assertThat(secondContext.get()).isSameAs(owner);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void regularIntervalDiscovery() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger discoveryCount = new AtomicInteger();

        NodeDiscovery mockDiscovery = () -> {
            discoveryCount.incrementAndGet();
            latch.countDown();
            return Future.succeededFuture(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
        };

        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(mockDiscovery, vertx, nodes -> {
        }, 100, 100);
        scheduler.start();
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveryCount.get()).isGreaterThanOrEqualTo(3);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void discoveryExceptionDoesNotStopScheduler() throws Exception {
        AtomicInteger discoveryCount = new AtomicInteger();
        CountDownLatch secondDiscovery = new CountDownLatch(2);

        NodeDiscovery mockDiscovery = () -> {
            int count = discoveryCount.incrementAndGet();
            secondDiscovery.countDown();
            if (count == 1) {
                return Future.failedFuture(new IOException("Simulated discovery failure"));
            }
            return Future.succeededFuture(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
        };

        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(mockDiscovery, vertx, nodes -> {
        }, 100, 100);
        scheduler.start();
        try {
            assertThat(secondDiscovery.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveryCount.get()).isGreaterThanOrEqualTo(2);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void emptyDiscoveryResultDoesNotCallUpdater() throws Exception {
        CountDownLatch discovered = new CountDownLatch(1);
        AtomicInteger updaterCalls = new AtomicInteger();

        NodeDiscovery mockDiscovery = () -> {
            discovered.countDown();
            return Future.succeededFuture(List.of());
        };

        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(mockDiscovery, vertx,
                nodes -> updaterCalls.incrementAndGet(), 60_000, 1_000);
        scheduler.start();
        try {
            assertThat(discovered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(updaterCalls.get()).isEqualTo(0);
        } finally {
            scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeStopsScheduler() throws Exception {
        AtomicInteger discoveryCount = new AtomicInteger();

        NodeDiscovery mockDiscovery = () -> {
            discoveryCount.incrementAndGet();
            return Future.succeededFuture(List.of(new NodeImpl(URI.create("http://discovered:9200"))));
        };

        NodeDiscoveryScheduler scheduler = new NodeDiscoveryScheduler(mockDiscovery, vertx, nodes -> {
        }, 100, 100);

        scheduler.start();
        Thread.sleep(200);
        scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        int countAfterClose = discoveryCount.get();
        Thread.sleep(500);
        assertThat(discoveryCount.get()).isEqualTo(countAfterClose);
    }
}
