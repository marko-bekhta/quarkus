package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.Roles;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * Default implementation of {@link Node} with all fields, dead-host tracking state,
 * and cached {@link RequestOptions} for efficient request creation.
 */
public final class NodeImpl implements Node {

    private final URI host;
    private final Set<URI> boundHosts;
    private final String name;
    private final String version;
    private final Roles roles;
    private final Map<String, List<String>> attributes;
    private final int resolvedPort;
    private final boolean ssl;
    private final RequestOptions baseRequestOptions;

    /**
     * Whether this node carries metadata (roles, version, attributes) that a
     * {@link io.quarkus.elasticsearch.restclient.vertx.NodeSelector} can meaningfully
     * inspect. See {@link #isDescribed()} for how this drives routing eligibility.
     */
    private final boolean described;

    final AtomicReference<DeadHostState> deadState = new AtomicReference<>();

    /**
     * Creates an <em>undescribed</em> seed node from a bare URI, as supplied to the client
     * builder. Such a node carries no metadata yet ({@code roles = }{@link Roles#NONE},
     * {@code version}/{@code attributes = null}); a metadata-based node selector cannot judge
     * it, so it is always eligible for routing until node discovery replaces it with a fully
     * described node. See {@link #isDescribed()}.
     */
    public NodeImpl(URI host) {
        this(host, null, null, null, null, null, false);
    }

    /**
     * Creates a fully <em>described</em> node, as produced by node discovery once it has
     * fetched the node's roles, version, and attributes from the cluster. Described nodes
     * are subject to node-selector filtering.
     */
    public NodeImpl(URI host, Set<URI> boundHosts, String name, String version,
            Roles roles, Map<String, List<String>> attributes) {
        this(host, boundHosts, name, version, roles, attributes, true);
    }

    private NodeImpl(URI host, Set<URI> boundHosts, String name, String version,
            Roles roles, Map<String, List<String>> attributes, boolean described) {
        this.host = Objects.requireNonNull(host, "host");
        this.boundHosts = boundHosts == null ? null : Set.copyOf(boundHosts);
        this.name = name;
        this.version = version;
        this.roles = roles != null ? roles : Roles.NONE;
        this.attributes = attributes == null ? null : Map.copyOf(attributes);
        this.resolvedPort = host.getPort() != -1 ? host.getPort() : HttpConstants.defaultPort(host.getScheme());
        this.ssl = HttpConstants.isSsl(host.getScheme());
        this.described = described;
        RequestOptions base = new RequestOptions();
        base.setHost(host.getHost());
        base.setPort(resolvedPort);
        base.setSsl(ssl);
        this.baseRequestOptions = base;
    }

    /**
     * Converts any {@link Node} implementation into a {@link NodeImpl}. If the given
     * node is already a {@code NodeImpl}, it is returned as-is.
     */
    public static NodeImpl from(Node node) {
        if (node instanceof NodeImpl impl) {
            return impl;
        }
        return new NodeImpl(node.getHost(), node.getBoundHosts(), node.getName(),
                node.getVersion(), node.getRoles(), node.getAttributes());
    }

    @Override
    public URI getHost() {
        return host;
    }

    @Override
    public Set<URI> getBoundHosts() {
        return boundHosts;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public Roles getRoles() {
        return roles;
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public int getResolvedPort() {
        return resolvedPort;
    }

    /**
     * Returns whether this node has been described by node discovery, i.e. whether its
     * roles, version, and attributes are known.
     * <p>
     * Only described nodes are passed to the {@link io.quarkus.elasticsearch.restclient.vertx.NodeSelector}
     * when the routable set is computed. Undescribed seed nodes (bare URIs from the builder)
     * are always kept eligible: a selector that filters on metadata would otherwise drop every
     * seed before discovery has had a chance to run, deadlocking the bootstrap. Once discovery
     * publishes described nodes, the selector applies to them in full.
     */
    public boolean isDescribed() {
        return described;
    }

    public RequestOptions newRequestOptions(String method, String uri) {
        RequestOptions options = new RequestOptions(baseRequestOptions);
        options.setMethod(HttpMethod.valueOf(method));
        options.setURI(uri);
        return options;
    }

    public boolean shouldBeRetried() {
        DeadHostState state = this.deadState.get();
        return state == null || state.shallBeRetried();
    }

    public boolean shouldBeRetried(long nowNanos) {
        DeadHostState state = this.deadState.get();
        return state == null || state.shallBeRetried(nowNanos);
    }

    void markDead(Supplier<Long> nanoTimeSupplier, BackoffStrategy backoffStrategy) {
        deadState.getAndUpdate(previous -> previous == null
                ? new DeadHostState(nanoTimeSupplier, backoffStrategy)
                : new DeadHostState(previous));
    }

    void markAlive() {
        deadState.set(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodeImpl node)) {
            return false;
        }
        return host.equals(node.host);
    }

    @Override
    public int hashCode() {
        return host.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Node{host=").append(host);
        if (name != null) {
            sb.append(", name='").append(name).append('\'');
        }
        if (version != null) {
            sb.append(", version='").append(version).append('\'');
        }
        if (roles != null) {
            sb.append(", roles=").append(roles);
        }
        return sb.append('}').toString();
    }
}
