package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.util.Objects;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;

/**
 * {@link NodeSelector} that applies two selectors in sequence to the same node set. Obtained via
 * {@link NodeSelector#and(NodeSelector)}.
 * <p>
 * Selectors run left-to-right, and each one sees the set already narrowed by the previous. Order
 * therefore matters: {@code skipDedicatedMasters().and(preferHasAttribute("zone", "z1"))} first
 * drops dedicated masters and then, among the survivors, prefers the {@code zone=z1} nodes.
 * <p>
 * Chaining more than two selectors nests instances ({@code a.and(b).and(c)}), which reads and
 * prints as {@code ((a and b) and c)}.
 */
public final class AndNodeSelector implements NodeSelector {

    private final NodeSelector first;
    private final NodeSelector second;

    public AndNodeSelector(NodeSelector first, NodeSelector second) {
        this.first = Objects.requireNonNull(first, "first");
        this.second = Objects.requireNonNull(second, "second");
    }

    @Override
    public void select(Iterable<? extends Node> nodes) {
        first.select(nodes);
        second.select(nodes);
    }

    @Override
    public String toString() {
        return "(" + first + " and " + second + ")";
    }
}
