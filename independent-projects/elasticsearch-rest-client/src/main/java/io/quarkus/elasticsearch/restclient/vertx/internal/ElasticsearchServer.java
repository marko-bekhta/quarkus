package io.quarkus.elasticsearch.restclient.vertx.internal;

import io.vertx.core.net.SocketAddress;

/**
 * Pairs a logical Elasticsearch {@link NodeImpl} with its resolved {@link SocketAddress}.
 * Used as the server type in the Vert.x {@code EndpointResolver} SPI so that node
 * identity is preserved through the endpoint lifecycle -- from resolution through
 * load balancing and back to response handling.
 */
record ElasticsearchServer(NodeImpl node, SocketAddress address) {

    ElasticsearchServer(NodeImpl node) {
        this(node, SocketAddress.inetSocketAddress(
                node.getResolvedPort(), node.getHost().getHost()));
    }
}
