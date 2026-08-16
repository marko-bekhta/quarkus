package io.quarkus.elasticsearch.restclient.vertx.internal;

import io.vertx.core.net.Address;

/**
 * Singleton {@link Address} representing the logical Elasticsearch cluster. Used by
 * {@link ResolverRequestDispatcher} to key endpoint resolution through the Vert.x
 * address resolver SPI.
 */
final class ElasticsearchAddress implements Address {

    public static final ElasticsearchAddress INSTANCE = new ElasticsearchAddress();

    private ElasticsearchAddress() {
    }

    @Override
    public String toString() {
        return "ElasticsearchAddress";
    }
}
