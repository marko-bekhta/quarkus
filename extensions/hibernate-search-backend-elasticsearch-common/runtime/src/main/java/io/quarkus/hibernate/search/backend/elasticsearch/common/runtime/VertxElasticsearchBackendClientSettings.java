package io.quarkus.hibernate.search.backend.elasticsearch.common.runtime;

/**
 * Settings specific to the Vert.x Elasticsearch client used by Hibernate Search.
 * Keys are relative to a Hibernate Search backend configuration prefix.
 */
public final class VertxElasticsearchBackendClientSettings {

    public static final String REQUEST_TIMEOUT = "vertx.request_timeout";
    public static final String DISCOVERY_ENABLED = "vertx.discovery.enabled";
    public static final String DISCOVERY_REFRESH_INTERVAL = "vertx.discovery.refresh_interval";

    public static final String CONNECT_TIMEOUT = "vertx.connect_timeout";
    public static final String READ_IDLE_TIMEOUT = "vertx.read_idle_timeout";
    public static final String HTTP1_MAX_POOL_SIZE = "vertx.http1_max_pool_size";

    private VertxElasticsearchBackendClientSettings() {
    }
}
