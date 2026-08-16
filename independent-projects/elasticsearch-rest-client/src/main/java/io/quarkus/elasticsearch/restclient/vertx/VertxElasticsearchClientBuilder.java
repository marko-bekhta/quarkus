package io.quarkus.elasticsearch.restclient.vertx;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/**
 * Builder for constructing a {@link VertxElasticsearchClient} with configurable options
 * such as HTTP client settings, a custom request dispatcher factory, and node discovery.
 * <p>
 * Dispatch-specific settings (node selector, default headers, compression, etc.) are
 * configured on the {@link RequestDispatcherFactory} rather than on this builder.
 */
public class VertxElasticsearchClientBuilder {

    private final Vertx vertx;
    private final URI[] initialHosts;
    private HttpClientOptions httpClientOptions;
    private PoolOptions poolOptions;
    private RequestDispatcherFactory requestDispatcherFactory;
    private NodeDiscoveryConfigurer nodeDiscoveryConfigurer;

    VertxElasticsearchClientBuilder(Vertx vertx, URI... hosts) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(hosts, "hosts");
        if (hosts.length == 0) {
            throw new IllegalArgumentException("At least one host is required");
        }
        if (new HashSet<>(List.of(hosts)).size() != hosts.length) {
            throw new IllegalArgumentException("Duplicate host URIs are not allowed");
        }
        this.initialHosts = hosts;
    }

    public VertxElasticsearchClientBuilder setHttpClientOptions(HttpClientOptions httpClientOptions) {
        this.httpClientOptions = httpClientOptions;
        return this;
    }

    public VertxElasticsearchClientBuilder setPoolOptions(PoolOptions poolOptions) {
        this.poolOptions = poolOptions;
        return this;
    }

    /**
     * Sets the request dispatcher factory that defines the dispatch strategy (round-robin,
     * Vert.x resolver, etc.) and its configuration. If not set, defaults to
     * {@link RequestDispatcher#roundRobin()}.
     *
     * @param requestDispatcherFactory the factory to use
     * @return this builder for chaining
     */
    public VertxElasticsearchClientBuilder setRequestDispatcher(RequestDispatcherFactory requestDispatcherFactory) {
        this.requestDispatcherFactory = requestDispatcherFactory;
        return this;
    }

    public VertxElasticsearchClientBuilder nodeDiscovery(Consumer<NodeDiscoveryConfigurer> configurer) {
        this.nodeDiscoveryConfigurer = new NodeDiscoveryConfigurer();
        configurer.accept(this.nodeDiscoveryConfigurer);
        return this;
    }

    public VertxElasticsearchClient build() {
        List<NodeImpl> nodes = new ArrayList<>(initialHosts.length);
        for (URI host : initialHosts) {
            nodes.add(new NodeImpl(host));
        }

        RequestDispatcherFactory factory = this.requestDispatcherFactory;
        if (factory == null) {
            factory = RequestDispatcher.roundRobin();
        }

        HttpConstants.Scheme scheme = deriveScheme(initialHosts);

        // The HTTP client is created and owned by the dispatcher (built from these options and
        // pool settings), not here -- different dispatch strategies need different flavors of
        // client. The builder only forwards the raw options.
        return new VertxElasticsearchClient(vertx, factory, nodes, nodeDiscoveryConfigurer, scheme,
                httpClientOptions, poolOptions);
    }

    private static HttpConstants.Scheme deriveScheme(URI[] hosts) {
        boolean hasSsl = false;
        boolean hasPlain = false;
        for (URI host : hosts) {
            if (HttpConstants.isSsl(host.getScheme())) {
                hasSsl = true;
            } else {
                hasPlain = true;
            }
        }
        if (hasSsl && hasPlain) {
            throw new IllegalArgumentException(
                    "All hosts must use the same URI scheme (http or https), but a mix was provided");
        }
        return hasSsl ? HttpConstants.Scheme.HTTPS : HttpConstants.Scheme.HTTP;
    }
}
