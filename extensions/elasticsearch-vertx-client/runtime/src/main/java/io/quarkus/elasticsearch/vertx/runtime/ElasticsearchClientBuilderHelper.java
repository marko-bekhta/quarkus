package io.quarkus.elasticsearch.vertx.runtime;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.elasticsearch.restclient.vertx.RequestDispatcher;
import io.quarkus.elasticsearch.restclient.vertx.RequestDispatcherFactory;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClientBuilder;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfig;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfigConfigurer;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

public final class ElasticsearchClientBuilderHelper {
    private static final Logger LOG = Logger.getLogger(ElasticsearchClientBuilderHelper.class);

    private ElasticsearchClientBuilderHelper() {
        // avoid instantiation
    }

    public static VertxElasticsearchClientBuilder createBuilder(Vertx vertx, ElasticsearchConfig config) {
        URI[] hosts = new URI[config.hosts().size()];
        for (int i = 0; i < hosts.length; i++) {
            InetSocketAddress host = config.hosts().get(i);
            try {
                hosts[i] = new URI(config.protocol(), null, host.getHostString(), host.getPort(), null, null, null);
            } catch (URISyntaxException e) {
                throw new ConfigurationException("Invalid Elasticsearch host: " + host, e);
            }
        }
        HttpClientOptions options = new HttpClientOptions()
                .setConnectTimeout(Math.toIntExact(config.connectTimeout().toMillis()))
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setReadIdleTimeout(Math.toIntExact(config.readIdleTimeout().toMillis()));
        RequestDispatcherFactory dispatcher = RequestDispatcher.roundRobin();
        applyAuthentication(dispatcher, config);
        VertxElasticsearchClientBuilder builder = VertxElasticsearchClient.builder(vertx, hosts)
                .setHttpClientOptions(options)
                .setPoolOptions(new PoolOptions().setHttp1MaxSize(config.http1MaxPoolSize()))
                .setRequestDispatcher(dispatcher);
        if (config.discovery().enabled()) {
            builder.nodeDiscovery(
                    discovery -> discovery.discoveryIntervalMillis(config.discovery().refreshInterval().toMillis()));
        }
        // Apply configuration from ElasticsearchClientConfigConfigurer implementations annotated with ElasticsearchClientConfig
        for (InstanceHandle<ElasticsearchClientConfigConfigurer> handle : Arc.container()
                .select(ElasticsearchClientConfigConfigurer.class, new ElasticsearchClientConfig.Literal()).handles()) {
            try (handle) {
                handle.get().accept(builder);
            }
        }
        return builder;
    }

    private static void applyAuthentication(RequestDispatcherFactory dispatcher, ElasticsearchConfig config) {
        boolean hasBasic = config.username().isPresent();
        boolean hasApiKey = config.apiKey().isPresent();
        if (hasBasic && hasApiKey) {
            throw new ConfigurationException("You must provide either a valid username/password pair for Basic " +
                    "authentication OR only a valid API key for ApiKey authentication. Both methods are currently enabled.");
        }
        if (!"https".equalsIgnoreCase(config.protocol()) && (hasBasic || hasApiKey)) {
            LOG.warn("Transmitting authentication information over HTTP is unsafe as it implies sending sensitive " +
                    "information as plain text over an unencrypted channel. Use the HTTPS protocol instead.");
        }
        if (hasBasic) {
            String credentials = config.username().get() + ":" + config.password().orElse("");
            dispatcher.defaultHeaders(Map.of("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))));
        } else if (hasApiKey) {
            dispatcher.defaultHeaders(Map.of("Authorization", "ApiKey " + config.apiKey().get()));
        }
    }
}
