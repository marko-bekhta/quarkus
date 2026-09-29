package io.quarkus.elasticsearch.restclient.vertx.internal;

import io.vertx.core.net.Address;

/**
 * Logical {@link Address} representing an Elasticsearch cluster routing policy. Used by
 * {@link ResolverRequestDispatcher} to key endpoint resolution through the Vert.x
 * address resolver SPI.
 */
final class ElasticsearchAddress implements Address {

    public static final ElasticsearchAddress INSTANCE = new ElasticsearchAddress();

    public static final ElasticsearchAddress DISCOVERY = new ElasticsearchAddress();

    private ElasticsearchAddress() {
    }

    @Override
    public String toString() {
        return this == DISCOVERY ? "ElasticsearchDiscoveryAddress" : "ElasticsearchAddress";
    }
}
