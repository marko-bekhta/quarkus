package io.quarkus.elasticsearch.restclient.vertx;

import io.quarkus.elasticsearch.restclient.vertx.internal.AndNodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.internal.AnyNodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.internal.PreferHasAttributeNodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.internal.SkipDedicatedMastersNodeSelector;

/**
 * Filters candidate nodes, removing nodes that should not receive requests. Implementations
 * modify the provided iterable in place.
 * <p>
 * A selector should be a pure function of node metadata (roles, attributes, version): it is
 * applied when the node set changes rather than per request, so its verdict for a given node
 * must not depend on request-time state.
 * <p>
 * Built-in selectors are obtained from the factory methods {@link #any()},
 * {@link #skipDedicatedMasters()}, and {@link #preferHasAttribute(String, String)}. Selectors
 * compose left-to-right with {@link #and(NodeSelector)}.
 */
public interface NodeSelector {

    /**
     * Filters the given candidate nodes in place. Implementations must use
     * {@link java.util.Iterator#remove()} to exclude nodes that should not
     * receive requests -- the collection is owned by the dispatcher and will
     * be mutated.
     *
     * @param nodes mutable iterable of candidate nodes; remove unwanted entries via its iterator
     */
    void select(Iterable<? extends Node> nodes);

    /**
     * Returns the selector that accepts every node (no filtering). This is the default.
     */
    static NodeSelector any() {
        return AnyNodeSelector.INSTANCE;
    }

    /**
     * Returns a selector that keeps requests off dedicated master-eligible nodes -- those that
     * are master-eligible but can neither contain data nor ingest. Nodes with any data or ingest
     * role, and nodes whose role metadata is unknown, are kept.
     */
    static NodeSelector skipDedicatedMasters() {
        return SkipDedicatedMastersNodeSelector.INSTANCE;
    }

    /**
     * Returns a selector that prefers nodes carrying the attribute {@code name=value} -- typically
     * for zone/rack/region awareness. The preference is soft: nodes without the attribute are
     * removed only when at least one node has it, so the selector can never empty the routable set
     * on its own. Attributes are multi-valued; a node matches when its values for {@code name}
     * contain {@code value}. Nodes with unknown attribute metadata are treated as non-matching.
     *
     * @param name the attribute name (e.g. {@code "zone"})
     * @param value the preferred attribute value (e.g. {@code "eu-west-1a"})
     */
    static NodeSelector preferHasAttribute(String name, String value) {
        return new PreferHasAttributeNodeSelector(name, value);
    }

    /**
     * Returns a selector that applies this selector and then {@code other} to the same node set.
     * Selectors run left-to-right, and {@code other} sees the set already narrowed by this one, so
     * ordering is significant.
     *
     * @param other the selector to apply after this one
     */
    default NodeSelector and(NodeSelector other) {
        return new AndNodeSelector(this, other);
    }
}
