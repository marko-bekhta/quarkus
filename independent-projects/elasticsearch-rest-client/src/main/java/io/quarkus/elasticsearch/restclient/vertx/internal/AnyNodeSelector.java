package io.quarkus.elasticsearch.restclient.vertx.internal;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;

/**
 * {@link NodeSelector} that accepts every node -- no filtering. Obtained via
 * {@link NodeSelector#any()}.
 * <p>
 * A stateless singleton: it is the default selector and dispatchers identity-check
 * against {@link #INSTANCE} to detect "no selector configured", so exactly one
 * instance must exist.
 */
public final class AnyNodeSelector implements NodeSelector {

    public static final AnyNodeSelector INSTANCE = new AnyNodeSelector();

    private AnyNodeSelector() {
    }

    @Override
    public void select(Iterable<? extends Node> nodes) {
        // Accept all nodes: nothing to remove.
    }

    @Override
    public String toString() {
        return "NodeSelector.any()";
    }
}
