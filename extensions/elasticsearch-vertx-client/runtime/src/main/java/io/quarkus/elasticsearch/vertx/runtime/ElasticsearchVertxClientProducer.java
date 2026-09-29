package io.quarkus.elasticsearch.vertx.runtime;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.Vertx;

@ApplicationScoped
public class ElasticsearchVertxClientProducer {
    @Inject
    ElasticsearchConfig config;

    @Inject
    Vertx vertx;

    private VertxElasticsearchClient client;

    @Produces
    @Singleton
    public VertxElasticsearchClient client() {
        client = ElasticsearchClientBuilderHelper.createBuilder(vertx, config).build();
        return client;
    }

    @PreDestroy
    void destroy() {
        if (client != null) {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
