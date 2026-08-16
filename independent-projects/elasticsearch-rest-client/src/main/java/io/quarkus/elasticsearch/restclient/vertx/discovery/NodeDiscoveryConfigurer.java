package io.quarkus.elasticsearch.restclient.vertx.discovery;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Vertx;

/**
 * Configures node-discovery parameters for use with the client builder. Passed as
 * a lambda to {@code VertxElasticsearchClientBuilder.nodeDiscovery(Consumer)}.
 */
public class NodeDiscoveryConfigurer {

    private static final long DEFAULT_DISCOVERY_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long DEFAULT_DISCOVERY_AFTER_FAILURE_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long DEFAULT_DISCOVERY_SERVER_TIMEOUT_MILLIS = 1_000;

    private long discoveryIntervalMillis = DEFAULT_DISCOVERY_INTERVAL_MILLIS;
    private long discoveryAfterFailureDelayMillis = DEFAULT_DISCOVERY_AFTER_FAILURE_DELAY_MILLIS;
    private long discoveryServerTimeoutMillis = DEFAULT_DISCOVERY_SERVER_TIMEOUT_MILLIS;
    private Function<VertxElasticsearchClient, NodeDiscovery> nodeDiscoveryFactory;

    public NodeDiscoveryConfigurer discoveryIntervalMillis(long discoveryIntervalMillis) {
        if (discoveryIntervalMillis <= 0) {
            throw new IllegalArgumentException("discoveryIntervalMillis must be positive");
        }
        this.discoveryIntervalMillis = discoveryIntervalMillis;
        return this;
    }

    public NodeDiscoveryConfigurer discoveryAfterFailureDelayMillis(long discoveryAfterFailureDelayMillis) {
        if (discoveryAfterFailureDelayMillis <= 0) {
            throw new IllegalArgumentException("discoveryAfterFailureDelayMillis must be positive");
        }
        this.discoveryAfterFailureDelayMillis = discoveryAfterFailureDelayMillis;
        return this;
    }

    /**
     * Sets the <em>server-side</em> timeout Elasticsearch applies while gathering node HTTP
     * information for discovery. It is sent as the {@code timeout} query parameter on
     * {@code GET /_nodes/http}, bounding how long the coordinating node waits for the rest of the
     * cluster to report before it responds.
     * <p>
     * This is <strong>not</strong> a client-side request timeout. The discovery HTTP call itself is
     * bounded only by the Vert.x {@code HttpClientOptions} connect/idle settings, not by this value;
     * a node that accepts the connection but never responds is not cut off by it. It is a hint to
     * the server, which may legitimately take longer -- the response still has to travel back over
     * the network. Consequently, any client-side timeout introduced later must be set comfortably
     * larger than this value (never equal to it), so the server has a chance to answer.
     *
     * @param discoveryServerTimeoutMillis the server-side node-info timeout in milliseconds (must be positive)
     * @return this configurer for chaining
     */
    public NodeDiscoveryConfigurer discoveryServerTimeoutMillis(long discoveryServerTimeoutMillis) {
        if (discoveryServerTimeoutMillis <= 0) {
            throw new IllegalArgumentException("discoveryServerTimeoutMillis must be positive");
        }
        this.discoveryServerTimeoutMillis = discoveryServerTimeoutMillis;
        return this;
    }

    public NodeDiscoveryConfigurer nodeDiscoveryFactory(Function<VertxElasticsearchClient, NodeDiscovery> factory) {
        this.nodeDiscoveryFactory = factory;
        return this;
    }

    public NodeDiscoveryScheduler createScheduler(VertxElasticsearchClient client, Vertx vertx,
            Consumer<List<NodeImpl>> nodeUpdater, HttpConstants.Scheme scheme) {
        NodeDiscovery nodeDiscovery;
        if (nodeDiscoveryFactory != null) {
            nodeDiscovery = nodeDiscoveryFactory.apply(client);
        } else {
            nodeDiscovery = new ElasticsearchNodeDiscovery(client, discoveryServerTimeoutMillis, scheme);
        }
        return new NodeDiscoveryScheduler(nodeDiscovery, vertx, nodeUpdater, discoveryIntervalMillis,
                discoveryAfterFailureDelayMillis);
    }
}
