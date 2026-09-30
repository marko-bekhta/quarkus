package io.quarkus.elasticsearch.vertx.runtime;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.elasticsearch")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface ElasticsearchConfig {

    /**
     * The list of hosts of the Elasticsearch servers.
     */
    @WithDefault("localhost:9200")
    List<InetSocketAddress> hosts();

    /**
     * The protocol to use when contacting Elasticsearch servers.
     * Set to "https" to enable SSL/TLS.
     */
    @WithDefault("http")
    String protocol();

    /**
     * The username for basic HTTP authentication.
     */
    Optional<String> username();

    /**
     * The password for basic HTTP authentication.
     */
    Optional<String> password();

    /**
     * The API key for authentication.
     */
    Optional<String> apiKey();

    /**
     * Timeout for establishing a connection to an Elasticsearch server.
     */
    @WithDefault("1S")
    Duration connectTimeout();

    /**
     * Maximum time without incoming data on a connection. Zero disables the timeout.
     */
    @WithDefault("30S")
    Duration readIdleTimeout();

    /**
     * Maximum number of pooled HTTP/1 connections per Elasticsearch server.
     */
    @WithDefault("20")
    int http1MaxPoolSize();

    /**
     * Legacy timeout for establishing a connection to an Elasticsearch server.
     *
     * @deprecated Use {@link #connectTimeout()} instead.
     */
    @Deprecated(since = "4.0")
    Optional<Duration> connectionTimeout();

    /**
     * Legacy maximum time without incoming data on a connection.
     *
     * @deprecated Use {@link #readIdleTimeout()} instead.
     */
    @Deprecated(since = "4.0")
    Optional<Duration> socketTimeout();

    /**
     * Legacy maximum number of connections per Elasticsearch server.
     *
     * @deprecated Use {@link #http1MaxPoolSize()} instead.
     */
    @Deprecated(since = "4.0")
    Optional<Integer> maxConnectionsPerRoute();

    /**
     * Configuration for the automatic discovery of new Elasticsearch nodes.
     */
    DiscoveryConfig discovery();

    @ConfigGroup
    interface DiscoveryConfig {

        /**
         * Defines if automatic discovery is enabled.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Refresh interval of the node list.
         */
        @WithDefault("5M")
        Duration refreshInterval();
    }
}
