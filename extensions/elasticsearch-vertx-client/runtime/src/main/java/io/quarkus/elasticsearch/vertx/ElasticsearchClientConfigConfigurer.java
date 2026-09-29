package io.quarkus.elasticsearch.vertx;

import java.util.function.Consumer;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClientBuilder;

/**
 * Allows customization of the underlying HTTP client used to connect to Elasticsearch.
 */
public interface ElasticsearchClientConfigConfigurer extends Consumer<VertxElasticsearchClientBuilder> {

}
