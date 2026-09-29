package io.quarkus.elasticsearch.vertx.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClientBuilder;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfig;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfigConfigurer;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.runtime.configuration.DurationConverter;
import io.quarkus.runtime.configuration.InetSocketAddressConverter;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.PfxOptions;

class ElasticsearchClientBehaviorTest {
    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(DisableKeepAliveCustomizer.class)
                    .addAsResource("elasticsearch-test.p12"))
            .overrideConfigKey("quarkus.elasticsearch.devservices.enabled", "false");

    @Inject
    Vertx vertx;

    private final List<VertxElasticsearchClient> clients = new ArrayList<>();
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void closeResources() throws Exception {
        for (VertxElasticsearchClient client : clients) {
            get(client.close());
        }
        for (HttpServer server : servers) {
            get(server.close());
        }
    }

    @Test
    void sendsBasicAuthenticationAndAppliesCustomizer() throws Exception {
        HttpServer server = server(request -> request.response()
                .putHeader("Seen-Connection", request.getHeader("Connection"))
                .end(request.getHeader("Authorization")));
        VertxElasticsearchClient client = client(server, Map.of("username", "elastic", "password", "secret"));

        var response = client.performRequest(new Request("GET", "/"));

        assertThat(response.getBody().toString()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("elastic:secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(response.getHeader("Seen-Connection")).isEqualTo("close");
    }

    @Test
    void sendsApiKeyAuthentication() throws Exception {
        HttpServer server = server(request -> request.response().end(request.getHeader("Authorization")));
        VertxElasticsearchClient client = client(server, Map.of("api-key", "encoded-api-key"));

        assertThat(client.performRequest(new Request("GET", "/")).getBody().toString())
                .isEqualTo("ApiKey encoded-api-key");
    }

    @Test
    void rejectsConflictingAuthentication() {
        ElasticsearchConfig config = config(9200,
                Map.of("username", "elastic", "password", "secret", "api-key", "encoded-api-key"));
        assertThatThrownBy(() -> ElasticsearchClientBuilderHelper.createBuilder(vertx, config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Both methods are currently enabled");
    }

    @Test
    void readIdleTimeoutFailsAnUnansweredRequest() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = server(request -> received.incrementAndGet());
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://127.0.0.1:" + server.actualPort()))
                .setHttpClientOptions(new HttpClientOptions()
                        .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                        .setReadIdleTimeout(100))
                .build();
        clients.add(client);

        var pending = client.performRequestAsync(new Request("GET", "/")).toCompletionStage().toCompletableFuture();
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void httpsUsesConfiguredTrustAndRejectsAnUntrustedCertificate() throws Exception {
        HttpServer server = get(vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                .setKeyCertOptions(new PfxOptions().setPath("elasticsearch-test.p12").setPassword("password")))
                .requestHandler(request -> request.response().end("secure"))
                .listen(0, "127.0.0.1"));
        servers.add(server);
        VertxElasticsearchClient trusted = client(server, Map.of("protocol", "https"));
        assertThat(trusted.performRequest(new Request("GET", "/")).getBody().toString()).isEqualTo("secure");

        VertxElasticsearchClientBuilder builder = ElasticsearchClientBuilderHelper.createBuilder(vertx,
                config(server.actualPort(), Map.of("protocol", "https")));
        builder.setHttpClientOptions(new HttpClientOptions());
        VertxElasticsearchClient untrusted = builder.build();
        clients.add(untrusted);
        assertThatThrownBy(() -> untrusted.performRequest(new Request("GET", "/")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void discoversAndUsesPublishedNodes() throws Exception {
        AtomicInteger discoveryRequests = new AtomicInteger();
        HttpServer discovered = server(request -> request.response().end("discovered"));
        String nodes = "{\"nodes\":{\"node-1\":{\"name\":\"discovered\",\"roles\":[\"data\"],"
                + "\"http\":{\"publish_address\":\"127.0.0.1:" + discovered.actualPort() + "\"}}}}";
        HttpServer seed = server(request -> {
            if ("/_nodes/http".equals(request.path())) {
                discoveryRequests.incrementAndGet();
                request.response().putHeader("Content-Type", "application/json").end(nodes);
            } else {
                request.response().end("seed");
            }
        });
        VertxElasticsearchClient client = client(seed,
                Map.of("discovery.enabled", "true", "discovery.refresh-interval", "1S"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(discoveryRequests.get()).isPositive();
            assertThat(client.performRequest(new Request("GET", "/")).getBody().toString()).isEqualTo("discovered");
        });
    }

    @Test
    void producerShutdownLeavesSharedVertxUsable() throws Exception {
        HttpServer server = server(request -> request.response().end("ok"));
        ElasticsearchVertxClientProducer producer = new ElasticsearchVertxClientProducer();
        // The runtime producer and the test are loaded by different Quarkus classloaders,
        // so package-private members require reflection even though the package names match.
        var vertxField = ElasticsearchVertxClientProducer.class.getDeclaredField("vertx");
        vertxField.setAccessible(true);
        vertxField.set(producer, vertx);
        var configField = ElasticsearchVertxClientProducer.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(producer, config(server.actualPort(), Map.of()));
        var destroy = ElasticsearchVertxClientProducer.class.getDeclaredMethod("destroy");
        destroy.setAccessible(true);
        VertxElasticsearchClient client = producer.client();
        try {
            assertThat(client.performRequest(new Request("GET", "/")).getBody().toString()).isEqualTo("ok");
        } finally {
            destroy.invoke(producer);
        }
        assertThatThrownBy(() -> client.performRequest(new Request("GET", "/"))).isInstanceOf(Exception.class);
        VertxElasticsearchClient replacement = client(server, Map.of());
        assertThat(replacement.performRequest(new Request("GET", "/")).getBody().toString()).isEqualTo("ok");
    }

    private HttpServer server(Handler<HttpServerRequest> handler) throws Exception {
        HttpServer server = get(vertx.createHttpServer().requestHandler(handler).listen(0, "127.0.0.1"));
        servers.add(server);
        return server;
    }

    private VertxElasticsearchClient client(HttpServer server, Map<String, String> values) {
        VertxElasticsearchClient client = ElasticsearchClientBuilderHelper.createBuilder(vertx,
                config(server.actualPort(), values)).build();
        clients.add(client);
        return client;
    }

    private ElasticsearchConfig config(int port, Map<String, String> values) {
        Map<String, String> properties = new HashMap<>();
        properties.put("quarkus.elasticsearch.hosts", "127.0.0.1:" + port);
        values.forEach((key, value) -> properties.put("quarkus.elasticsearch." + key, value));
        return new SmallRyeConfigBuilder().withMapping(ElasticsearchConfig.class)
                .withConverter(InetSocketAddress.class, 100, new InetSocketAddressConverter())
                .withConverter(Duration.class, 100, new DurationConverter())
                .withSources(new MapBackedConfigSource("test", properties) {
                })
                .build().getConfigMapping(ElasticsearchConfig.class);
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ElasticsearchClientConfig
    public static class DisableKeepAliveCustomizer implements ElasticsearchClientConfigConfigurer {
        @Override
        public void accept(VertxElasticsearchClientBuilder builder) {
            builder.setHttpClientOptions(new HttpClientOptions().setKeepAlive(false)
                    .setTrustOptions(new PfxOptions().setPath("elasticsearch-test.p12").setPassword("password")));
        }
    }
}
