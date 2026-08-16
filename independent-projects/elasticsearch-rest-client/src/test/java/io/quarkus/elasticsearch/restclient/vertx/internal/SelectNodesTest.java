package io.quarkus.elasticsearch.restclient.vertx.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Roles;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;

class SelectNodesTest {

    @Test
    void allAliveRoundRobin() throws IOException {
        AtomicLong clock = new AtomicLong(0);
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), clock::get);
        dispatcher.setNodes(List.of(
                node("http://a:9200"),
                node("http://b:9200"),
                node("http://c:9200")));

        List<NodeImpl> first = dispatcher.selectNodes();
        List<NodeImpl> second = dispatcher.selectNodes();
        List<NodeImpl> third = dispatcher.selectNodes();

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(third).hasSize(3);
        assertThat(first.get(0).getHost()).isNotEqualTo(second.get(0).getHost());
        assertThat(second.get(0).getHost()).isNotEqualTo(third.get(0).getHost());
    }

    @Test
    void deadNodeExcludedBeforeBackoffExpires() throws IOException {
        AtomicLong clock = new AtomicLong(0);
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), clock::get);
        NodeImpl a = node("http://a:9200");
        NodeImpl b = node("http://b:9200");
        NodeImpl c = node("http://c:9200");
        dispatcher.setNodes(List.of(a, b, c));

        dispatcher.markDead(b); // deadUntil = 0 + 60s
        clock.set(TimeUnit.SECONDS.toNanos(30)); // still within backoff

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).extracting(n -> n.getHost().getHost())
                .containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void deadNodeRevivedAfterBackoffExpires() throws IOException {
        AtomicLong clock = new AtomicLong(0);
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), clock::get);
        NodeImpl a = node("http://a:9200");
        NodeImpl b = node("http://b:9200");
        dispatcher.setNodes(List.of(a, b));

        dispatcher.markDead(b); // deadUntil = 0 + 60s
        clock.set(TimeUnit.SECONDS.toNanos(61)); // backoff expired

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(n -> n.getHost().getHost()).contains("b");
    }

    @Test
    void allDeadReturnsLeastDead() throws IOException {
        AtomicLong clock = new AtomicLong(0);
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), clock::get);
        NodeImpl a = node("http://a:9200");
        NodeImpl b = node("http://b:9200");
        dispatcher.setNodes(List.of(a, b));

        dispatcher.markDead(a); // deadUntil = 0 + 60s
        clock.set(TimeUnit.SECONDS.toNanos(10));
        dispatcher.markDead(b); // deadUntil = 10 + 60 = 70s
        clock.set(TimeUnit.SECONDS.toNanos(20)); // both still dead

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHost().getHost()).isEqualTo("a"); // earliest revival
    }

    @Test
    void selectorRejectsLivingFallsBackToDead() throws IOException {
        AtomicLong clock = new AtomicLong(0);
        NodeSelector removeA = nodes -> {
            var it = nodes.iterator();
            while (it.hasNext()) {
                if (it.next().getHost().getHost().equals("a")) {
                    it.remove();
                }
            }
        };
        DefaultRequestDispatcher dispatcher = createDispatcher(removeA, clock::get);
        // Described nodes so the selector applies to them (seed nodes always stay eligible).
        NodeImpl a = described("http://a:9200");
        NodeImpl b = described("http://b:9200");
        NodeImpl c = described("http://c:9200");
        dispatcher.setNodes(List.of(a, b, c));

        dispatcher.markDead(b); // deadUntil = 60s
        clock.set(TimeUnit.SECONDS.toNanos(5));
        dispatcher.markDead(c); // deadUntil = 65s
        clock.set(TimeUnit.SECONDS.toNanos(10)); // a living but filtered out, b & c dead

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHost().getHost()).isEqualTo("b"); // least dead
    }

    @Test
    void emptyNodeListThrows() {
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), System::nanoTime);
        assertThatThrownBy(dispatcher::selectNodes)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No nodes are configured");
    }

    @Test
    void singleNodeAlwaysReturned() throws IOException {
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), System::nanoTime);
        dispatcher.setNodes(List.of(node("http://a:9200")));

        for (int i = 0; i < 5; i++) {
            List<NodeImpl> result = dispatcher.selectNodes();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHost()).isEqualTo(URI.create("http://a:9200"));
        }
    }

    @Test
    void nodeSelectorFiltersNodes() throws IOException {
        DefaultRequestDispatcher dispatcher = createDispatcher(
                nodes -> {
                    var it = nodes.iterator();
                    while (it.hasNext()) {
                        if (it.next().getHost().getHost().equals("b")) {
                            it.remove();
                        }
                    }
                }, System::nanoTime);
        dispatcher.setNodes(List.of(
                described("http://a:9200"),
                described("http://b:9200"),
                described("http://c:9200")));

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(n -> n.getHost().getHost())
                .doesNotContain("b");
    }

    @Test
    void nodeSelectorRejectsAllThrows() {
        DefaultRequestDispatcher dispatcher = createDispatcher(
                nodes -> {
                    var it = nodes.iterator();
                    while (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }, System::nanoTime);
        dispatcher.setNodes(List.of(described("http://a:9200")));

        assertThatThrownBy(dispatcher::selectNodes)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("filtered out all");
    }

    @Test
    void seedNodesAreNeverFilteredBySelector() throws IOException {
        // A selector that would drop everything must not touch undescribed seed nodes;
        // otherwise a metadata-based selector would deadlock the bootstrap before discovery runs.
        DefaultRequestDispatcher dispatcher = createDispatcher(
                nodes -> {
                    var it = nodes.iterator();
                    while (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }, System::nanoTime);
        dispatcher.setNodes(List.of(
                node("http://a:9200"),
                node("http://b:9200")));

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).extracting(n -> n.getHost().getHost())
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void describedNodesFilteredWhileSeedsKept() throws IOException {
        // A selector filtering on metadata drops the described node but leaves the seed eligible.
        NodeSelector dropDataRole = nodes -> {
            var it = nodes.iterator();
            while (it.hasNext()) {
                if (it.next().getRoles().has("data")) {
                    it.remove();
                }
            }
        };
        DefaultRequestDispatcher dispatcher = createDispatcher(dropDataRole, System::nanoTime);
        NodeImpl seed = node("http://seed:9200");
        NodeImpl dataNode = new NodeImpl(URI.create("http://data:9200"), null, null, null,
                new Roles(Set.of("data")), null);
        dispatcher.setNodes(List.of(seed, dataNode));

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).extracting(n -> n.getHost().getHost())
                .containsExactly("seed");
    }

    @Test
    void setNodesRejectsNull() {
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), System::nanoTime);
        assertThatThrownBy(() -> dispatcher.setNodes(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void setNodesRejectsEmpty() {
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), System::nanoTime);
        assertThatThrownBy(() -> dispatcher.setNodes(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setNodesStoresAllNodesAsGiven() {
        DefaultRequestDispatcher dispatcher = createDispatcher(NodeSelector.any(), System::nanoTime);
        NodeImpl first = new NodeImpl(URI.create("http://a:9200"), null, "first", null, null, null);
        NodeImpl second = new NodeImpl(URI.create("http://a:9200"), null, "second", null, null, null);
        dispatcher.setNodes(List.of(first, second));

        assertThat(dispatcher.getNodes()).hasSize(2);
        assertThat(dispatcher.getNodes().get(0).getName()).isEqualTo("first");
        assertThat(dispatcher.getNodes().get(1).getName()).isEqualTo("second");
    }

    @Test
    void skipDedicatedMastersWithSelectNodes() throws IOException {
        DefaultRequestDispatcher dispatcher = createDispatcher(
                NodeSelector.skipDedicatedMasters(), System::nanoTime);
        NodeImpl masterOnly = new NodeImpl(URI.create("http://master:9200"), null, null, null,
                new Roles(Set.of("master")), null);
        NodeImpl dataNode = new NodeImpl(URI.create("http://data:9200"), null, null, null,
                new Roles(Set.of("data")), null);
        dispatcher.setNodes(List.of(masterOnly, dataNode));

        List<NodeImpl> result = dispatcher.selectNodes();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHost().getHost()).isEqualTo("data");
    }

    private static DefaultRequestDispatcher createDispatcher(NodeSelector selector, Supplier<Long> clock) {
        // A fixed 60s backoff keeps the dead-until offsets in these tests deterministic
        // (the default strategy applies random jitter).
        return new DefaultRequestDispatcher(selector, FailureListener.NO_OP,
                Map.of(), null, false, WarningsHandler.PERMISSIVE, clock,
                BackoffStrategy.fixed(Duration.ofSeconds(60)));
    }

    // A bare seed node (no metadata); always eligible for routing regardless of the selector.
    private static NodeImpl node(String uri) {
        return new NodeImpl(URI.create(uri));
    }

    // A discovered/described node (metadata known); subject to node-selector filtering.
    private static NodeImpl described(String uri) {
        return new NodeImpl(URI.create(uri), null, null, null, Roles.NONE, null);
    }
}
