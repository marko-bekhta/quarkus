package io.quarkus.elasticsearch.vertx.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.configuration.DurationConverter;
import io.quarkus.runtime.configuration.InetSocketAddressConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;

class ElasticsearchConfigAliasesTest {
    private static final String PREFIX = "quarkus.elasticsearch.";

    @Test
    void aliasesAndCanonicalPriority() {
        for (String[] pair : new String[][] {
                { "connect-timeout", "connection-timeout" },
                { "read-idle-timeout", "socket-timeout" },
                { "http1-max-pool-size", "max-connections-per-route" } }) {
            assertThat(config(Map.of(pair[1], "12"), Map.of()).getValue(PREFIX + pair[0], String.class)).isEqualTo("12");
            assertThat(config(Map.of(pair[0], "13"), Map.of()).getValue(PREFIX + pair[0], String.class)).isEqualTo("13");
            assertThat(config(Map.of(pair[0], "13", pair[1], "12"), Map.of()).getValue(PREFIX + pair[0], String.class))
                    .isEqualTo("13");
            assertThat(config(Map.of(pair[0], "13"), Map.of(pair[1], "12")).getValue(PREFIX + pair[0], String.class))
                    .isEqualTo("12");
            assertThat(config(Map.of(pair[1], "12"), Map.of(pair[0], "13")).getValue(PREFIX + pair[0], String.class))
                    .isEqualTo("13");
        }
    }

    @Test
    void canonicalNameWinsEqualPriorityProfileAlias() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().withProfile("test")
                .withInterceptorFactories(new ElasticsearchConfigAliases())
                .withSources(new MapBackedConfigSource("properties", Map.of(
                        PREFIX + "connect-timeout", "1S",
                        "%test." + PREFIX + "connection-timeout", "2S"), 100) {
                })
                .build();
        // Names use normal source precedence; at equal priority the canonical name wins.
        assertThat(config.getValue(PREFIX + "connect-timeout", String.class)).isEqualTo("1S");
    }

    @Test
    void mappingDefaultsAndEnvironmentAliases() {
        ElasticsearchConfig defaults = mappingBuilder().build().getConfigMapping(ElasticsearchConfig.class);
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.readIdleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.http1MaxPoolSize()).isEqualTo(20);
        ElasticsearchConfig environment = mappingBuilder().withSources(new EnvConfigSource(Map.of(
                "QUARKUS_ELASTICSEARCH_CONNECTION_TIMEOUT", "2S",
                "QUARKUS_ELASTICSEARCH_SOCKET_TIMEOUT", "3S",
                "QUARKUS_ELASTICSEARCH_MAX_CONNECTIONS_PER_ROUTE", "7"), 300))
                .build().getConfigMapping(ElasticsearchConfig.class);
        assertThat(environment.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(environment.readIdleTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(environment.http1MaxPoolSize()).isEqualTo(7);
    }

    private SmallRyeConfigBuilder mappingBuilder() {
        return new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withInterceptorFactories(new ElasticsearchConfigAliases())
                .withConverter(Duration.class, 100, new DurationConverter())
                .withConverter(InetSocketAddress.class, 100, new InetSocketAddressConverter())
                .withMapping(ElasticsearchConfig.class);
    }

    private SmallRyeConfig config(Map<String, String> low, Map<String, String> high) {
        return new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withInterceptorFactories(new ElasticsearchConfigAliases())
                .withSources(source("low", low, 100), source("high", high, 200)).build();
    }

    private MapBackedConfigSource source(String name, Map<String, String> values, int ordinal) {
        Map<String, String> prefixed = new HashMap<>();
        values.forEach((key, value) -> prefixed.put(PREFIX + key, value));
        return new MapBackedConfigSource(name, prefixed, ordinal) {
        };
    }
}
