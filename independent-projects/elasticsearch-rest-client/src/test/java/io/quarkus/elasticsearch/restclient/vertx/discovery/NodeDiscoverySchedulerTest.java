package io.quarkus.elasticsearch.restclient.vertx.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Future;
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
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveryCount.get()).isGreaterThanOrEqualTo(1);
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

        Thread.sleep(200);
        scheduler.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        int countAfterClose = discoveryCount.get();
        Thread.sleep(500);
        assertThat(discoveryCount.get()).isEqualTo(countAfterClose);
    }
}
