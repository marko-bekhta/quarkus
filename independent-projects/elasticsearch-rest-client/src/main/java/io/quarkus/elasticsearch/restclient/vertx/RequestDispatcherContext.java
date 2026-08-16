package io.quarkus.elasticsearch.restclient.vertx;

import java.util.List;

import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/**
 * Runtime context provided to a {@link RequestDispatcherFactory} when creating
 * a {@link RequestDispatcher}. Contains the client reference, initial node list,
 * optional node-discovery configuration, the derived URI scheme, and the Vert.x instance.
 */
record RequestDispatcherContext(
        VertxElasticsearchClient client,
        List<NodeImpl> initialNodes,
        NodeDiscoveryConfigurer nodeDiscoveryConfigurer,
        HttpConstants.Scheme scheme,
        Vertx vertx,
        HttpClientOptions httpClientOptions,
        PoolOptions poolOptions) {
}
