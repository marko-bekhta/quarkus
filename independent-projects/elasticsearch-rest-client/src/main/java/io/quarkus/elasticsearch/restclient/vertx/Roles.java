package io.quarkus.elasticsearch.restclient.vertx;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The set of roles assigned to an Elasticsearch or OpenSearch node, kept as raw role strings.
 * <p>
 * Role names are treated as opaque, version- and distribution-specific strings (for example
 * {@code master}, {@code cluster_manager}, {@code data}, {@code data_hot}, {@code ingest},
 * {@code ml}, {@code search}) rather than a fixed enumeration -- the vocabulary differs between
 * Elasticsearch and OpenSearch and grows over time. This type only answers membership questions;
 * any role-specific semantics (what counts as a data node, which name means master, and so on)
 * belong to whoever asks, such as a {@link NodeSelector}.
 */
public record Roles(Set<String> roles) {

    /** An empty role set, used when a node's roles are unknown. */
    public static final Roles NONE = new Roles(Set.of());

    public Roles(Set<String> roles) {
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    /**
     * Whether this node has the given role.
     */
    public boolean has(String role) {
        return roles.contains(role);
    }

    /**
     * Whether this node has at least one of the given roles.
     */
    public boolean hasAny(String... roles) {
        for (String role : roles) {
            if (this.roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any of this node's roles matches the given predicate -- useful for family checks
     * such as {@code anyMatch(r -> r.startsWith("data_"))}.
     */
    public boolean anyMatch(Predicate<String> predicate) {
        for (String role : roles) {
            if (predicate.test(role)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return roles.toString();
    }
}
