package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;

/**
 * {@link NodeSelector} that prefers nodes carrying a given attribute value -- typically for
 * zone/rack/region awareness (e.g. {@code zone=eu-west-1a}). Obtained via
 * {@link NodeSelector#preferHasAttribute(String, String)}.
 * <p>
 * The preference is <em>soft</em>: if at least one node has the attribute value, every node
 * that does not is removed; if <em>no</em> node has it, the set is left untouched. This means
 * the selector can never empty the routable set on its own -- it narrows routing when the
 * preferred location is available and transparently falls back to all nodes when it is not.
 * <p>
 * A node whose attribute metadata is unknown ({@link Node#getAttributes()} is {@code null})
 * is treated as non-matching. Attributes are multi-valued, so a node matches when its list of
 * values for {@code name} contains {@code value}.
 * <p>
 * Parameterized, so -- unlike the parameterless built-ins -- each call to the factory returns a
 * fresh instance rather than a shared singleton.
 */
public final class PreferHasAttributeNodeSelector implements NodeSelector {

    private final String name;
    private final String value;

    public PreferHasAttributeNodeSelector(String name, String value) {
        this.name = Objects.requireNonNull(name, "name");
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public void select(Iterable<? extends Node> nodes) {
        boolean anyMatch = false;
        for (Node node : nodes) {
            if (hasAttribute(node)) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            // The preferred attribute is absent everywhere -- keep all nodes.
            return;
        }
        Iterator<? extends Node> it = nodes.iterator();
        while (it.hasNext()) {
            if (!hasAttribute(it.next())) {
                it.remove();
            }
        }
    }

    private boolean hasAttribute(Node node) {
        Map<String, List<String>> attributes = node.getAttributes();
        if (attributes == null) {
            return false;
        }
        List<String> values = attributes.get(name);
        return values != null && values.contains(value);
    }

    @Override
    public String toString() {
        return "NodeSelector.preferHasAttribute(" + name + "=" + value + ")";
    }
}
