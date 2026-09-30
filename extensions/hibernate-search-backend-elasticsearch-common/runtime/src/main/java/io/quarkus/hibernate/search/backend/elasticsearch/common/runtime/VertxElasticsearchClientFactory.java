package io.quarkus.hibernate.search.backend.elasticsearch.common.runtime;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchBackendSettings;
import org.hibernate.search.backend.elasticsearch.client.common.gson.spi.GsonProvider;
import org.hibernate.search.backend.elasticsearch.client.common.spi.ElasticsearchClientFactory;
import org.hibernate.search.backend.elasticsearch.client.common.spi.ElasticsearchClientImplementor;
import org.hibernate.search.backend.elasticsearch.client.common.spi.ElasticsearchRequest;
import org.hibernate.search.backend.elasticsearch.client.common.spi.ElasticsearchResponse;
import org.hibernate.search.backend.elasticsearch.logging.spi.ElasticsearchClientLog;
import org.hibernate.search.engine.cfg.ConfigurationPropertySource;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.OptionalConfigurationProperty;
import org.hibernate.search.engine.common.execution.spi.SimpleScheduledExecutor;
import org.hibernate.search.engine.common.timing.Deadline;
import org.hibernate.search.engine.environment.bean.BeanResolver;
import org.hibernate.search.engine.environment.thread.spi.ThreadProvider;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.quarkus.arc.Arc;
import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.RequestDispatcher;
import io.quarkus.elasticsearch.restclient.vertx.RequestDispatcherFactory;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClientBuilder;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/** A separate, backend-owned transport for each Hibernate Search backend. */
public final class VertxElasticsearchClientFactory implements ElasticsearchClientFactory {

    private static final OptionalConfigurationProperty<List<String>> HOSTS = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.HOSTS).asString().multivalued().build();
    private static final OptionalConfigurationProperty<String> PROTOCOL = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.PROTOCOL).asString().build();
    private static final OptionalConfigurationProperty<List<String>> URIS = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.URIS).asString().multivalued().build();
    private static final ConfigurationProperty<String> PATH_PREFIX = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.PATH_PREFIX).asString()
            .withDefault(ElasticsearchBackendSettings.Defaults.PATH_PREFIX).build();
    private static final OptionalConfigurationProperty<String> USERNAME = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.USERNAME).asString().build();
    private static final OptionalConfigurationProperty<String> PASSWORD = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.PASSWORD).asString().build();
    private static final OptionalConfigurationProperty<Integer> REQUEST_TIMEOUT = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.REQUEST_TIMEOUT).asIntegerStrictlyPositive().build();
    private static final ConfigurationProperty<Integer> CONNECTION_TIMEOUT = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.CONNECTION_TIMEOUT).asIntegerPositiveOrZeroOrNegative()
            .withDefault(1000).build();
    private static final ConfigurationProperty<Integer> READ_TIMEOUT = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.READ_TIMEOUT).asIntegerPositiveOrZeroOrNegative()
            .withDefault(30000).build();
    private static final ConfigurationProperty<Integer> MAX_CONNECTIONS_PER_ROUTE = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.MAX_CONNECTIONS_PER_ROUTE).asIntegerStrictlyPositive()
            .withDefault(20).build();
    private static final ConfigurationProperty<Integer> MAX_CONNECTIONS = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.MAX_CONNECTIONS).asIntegerStrictlyPositive()
            .withDefault(40).build();
    private static final ConfigurationProperty<Boolean> DISCOVERY_ENABLED = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.DISCOVERY_ENABLED).asBoolean().withDefault(false).build();
    private static final ConfigurationProperty<Integer> DISCOVERY_REFRESH_INTERVAL = ConfigurationProperty
            .forKey(ElasticsearchBackendSettings.DISCOVERY_REFRESH_INTERVAL).asIntegerStrictlyPositive()
            .withDefault(10).build();
    private static final OptionalConfigurationProperty<Integer> VERTX_CONNECT_TIMEOUT = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.CONNECT_TIMEOUT).asIntegerPositiveOrZeroOrNegative().build();
    private static final OptionalConfigurationProperty<Integer> VERTX_READ_IDLE_TIMEOUT = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.READ_IDLE_TIMEOUT).asIntegerPositiveOrZeroOrNegative().build();
    private static final OptionalConfigurationProperty<Integer> VERTX_HTTP1_MAX_POOL_SIZE = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.HTTP1_MAX_POOL_SIZE).asIntegerStrictlyPositive().build();
    private static final OptionalConfigurationProperty<Integer> VERTX_REQUEST_TIMEOUT = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.REQUEST_TIMEOUT).asIntegerStrictlyPositive().build();
    private static final OptionalConfigurationProperty<Boolean> VERTX_DISCOVERY_ENABLED = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.DISCOVERY_ENABLED).asBoolean().build();
    private static final OptionalConfigurationProperty<Integer> VERTX_DISCOVERY_REFRESH_INTERVAL = ConfigurationProperty
            .forKey(VertxElasticsearchBackendClientSettings.DISCOVERY_REFRESH_INTERVAL).asIntegerStrictlyPositive().build();
    private static final Pattern CHARSET = Pattern.compile("(?:;|^)\\s*charset\\s*=\\s*\"?([^\"\\s;]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public ElasticsearchClientImplementor create(BeanResolver beanResolver, ConfigurationPropertySource properties,
            ThreadProvider threadProvider, String threadNamePrefix, SimpleScheduledExecutor timeoutExecutor,
            GsonProvider gsonProvider) {
        URI[] hosts = hosts(properties);
        RequestDispatcherFactory dispatcher = RequestDispatcher.roundRobin();
        String pathPrefix = PATH_PREFIX.get(properties);
        if (!pathPrefix.isEmpty()) {
            dispatcher.pathPrefix(pathPrefix);
        }
        USERNAME.get(properties).ifPresent(username -> dispatcher.defaultHeaders(Map.of("Authorization", "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + PASSWORD.get(properties).orElse(""))
                        .getBytes(StandardCharsets.UTF_8)))));
        HttpClientOptions options = new HttpClientOptions()
                .setConnectTimeout(VERTX_CONNECT_TIMEOUT.get(properties).orElseGet(() -> CONNECTION_TIMEOUT.get(properties)))
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setReadIdleTimeout(VERTX_READ_IDLE_TIMEOUT.get(properties).orElseGet(() -> READ_TIMEOUT.get(properties)));
        Vertx vertx = Arc.container().instance(Vertx.class).get();
        VertxElasticsearchClientBuilder builder = VertxElasticsearchClient.builder(vertx, hosts)
                .setHttpClientOptions(options)
                .setPoolOptions(new PoolOptions()
                        .setHttp1MaxSize(VERTX_HTTP1_MAX_POOL_SIZE.get(properties)
                                .orElseGet(() -> Math.min(MAX_CONNECTIONS_PER_ROUTE.get(properties),
                                        MAX_CONNECTIONS.get(properties)))))
                .setRequestDispatcher(dispatcher);
        if (VERTX_DISCOVERY_ENABLED.get(properties).orElseGet(() -> DISCOVERY_ENABLED.get(properties))) {
            builder.nodeDiscovery(config -> config.discoveryIntervalMillis(
                    TimeUnit.SECONDS.toMillis(VERTX_DISCOVERY_REFRESH_INTERVAL.get(properties)
                            .orElseGet(() -> DISCOVERY_REFRESH_INTERVAL.get(properties)))));
        }
        return new Client(vertx, builder.build(), gsonProvider.getGson(), timeoutExecutor,
                VERTX_REQUEST_TIMEOUT.get(properties).or(() -> REQUEST_TIMEOUT.get(properties)));
    }

    private static URI[] hosts(ConfigurationPropertySource properties) {
        Optional<List<String>> uris = URIS.get(properties);
        Optional<List<String>> configuredHosts = HOSTS.get(properties);
        Optional<String> protocol = PROTOCOL.get(properties);
        if (uris.isPresent() && (configuredHosts.isPresent() || protocol.isPresent())) {
            throw new IllegalArgumentException("Elasticsearch URIs cannot be combined with hosts or protocol");
        }
        if (uris.isPresent()) {
            return uris.get().stream().map(value -> URI.create(value.contains("://") ? value : "http://" + value))
                    .toArray(URI[]::new);
        }
        String scheme = protocol.orElse(ElasticsearchBackendSettings.Defaults.PROTOCOL);
        return configuredHosts.orElse(ElasticsearchBackendSettings.Defaults.HOSTS).stream()
                .map(host -> URI.create(scheme + "://" + host)).toArray(URI[]::new);
    }

    static final class Client implements ElasticsearchClientImplementor {
        private final Vertx vertx;
        private final VertxElasticsearchClient delegate;
        private final Gson gson;
        private final SimpleScheduledExecutor timeoutExecutor;
        private final Optional<Integer> requestTimeout;

        Client(Vertx vertx, VertxElasticsearchClient delegate, Gson gson, SimpleScheduledExecutor timeoutExecutor,
                Optional<Integer> requestTimeout) {
            this.vertx = vertx;
            this.delegate = delegate;
            this.gson = gson;
            this.timeoutExecutor = timeoutExecutor;
            this.requestTimeout = requestTimeout;
        }

        @Override
        public CompletableFuture<ElasticsearchResponse> submit(ElasticsearchRequest request) {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            Buffer body = null;
            if (!request.bodyParts().isEmpty()) {
                body = Buffer.buffer();
                for (JsonObject part : request.bodyParts()) {
                    body.appendString(gson.toJson(part)).appendByte((byte) '\n');
                }
                headers.add("Content-Type", "application/json");
            }

            Request outgoing = new Request(request.method(), request.path(), request.parameters(), headers, body, null);
            CancellableFuture<Response> pending = delegate.performRequestAsync(outgoing);

            CompletableFuture<ElasticsearchResponse> result = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) {
                        pending.cancel();
                    }
                    return cancelled;
                }
            };

            pending.onComplete(outcome -> {
                if (outcome.failed()) {
                    result.completeExceptionally(outcome.cause());
                    return;
                }
                if (result.isDone()) {
                    return;
                }

                Response response = outcome.result();
                vertx.<Void> executeBlocking(() -> {
                    try {
                        Buffer responseBody = response.getBody();
                        JsonObject json = responseBody == null || responseBody.length() == 0
                                ? null
                                : gson.fromJson(responseBody.toString(responseCharset(response)), JsonObject.class);
                        result.complete(new ElasticsearchResponse(response.getNode().getAuthority(),
                                response.getStatusCode(), response.getStatusMessage(), json));
                    } catch (RuntimeException e) {
                        result.completeExceptionally(ElasticsearchClientLog.INSTANCE.failedToParseElasticsearchResponse(
                                response.getStatusCode(), response.getStatusMessage(), e.getMessage(), e));
                    }
                    return null;
                }, false).onFailure(result::completeExceptionally);
            });

            Deadline deadline = request.deadline();
            if (deadline != null || requestTimeout.isPresent()) {
                long millis = deadline == null ? requestTimeout.get() : deadline.checkRemainingTimeMillis();
                ScheduledFuture<?> timeout = timeoutExecutor.schedule(() -> {
                    RuntimeException cause = ElasticsearchClientLog.INSTANCE.requestTimedOut(
                            Duration.ofMillis(millis), request);
                    if (result.completeExceptionally(
                            deadline == null ? cause : deadline.forceTimeoutAndCreateException(cause))) {
                        pending.cancel();
                    }
                }, millis, TimeUnit.MILLISECONDS);
                result.whenComplete((ignored, failure) -> timeout.cancel(false));
            }
            return result;
        }

        private static Charset responseCharset(Response response) {
            String type = response.getHeader("Content-Type");
            if (type != null) {
                Matcher matcher = CHARSET.matcher(type);
                if (matcher.find()) {
                    return Charset.forName(matcher.group(1));
                }
            }
            return StandardCharsets.UTF_8;
        }

        @Override
        public <T> T unwrap(Class<T> clientClass) {
            if (clientClass.isInstance(delegate)) {
                return clientClass.cast(delegate);
            }
            throw ElasticsearchClientLog.INSTANCE.clientUnwrappingWithUnknownType(clientClass, VertxElasticsearchClient.class);
        }

        @Override
        public void close() {
            var closing = delegate.close();
            if (!Context.isOnEventLoopThread()) {
                closing.toCompletionStage().toCompletableFuture().join();
            }
        }
    }
}
