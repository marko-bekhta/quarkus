package io.quarkus.elasticsearch.javaclient.runtime;

import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransportBase;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;

final class VertxElasticsearchTransport extends ElasticsearchTransportBase {

    VertxElasticsearchTransport(VertxElasticsearchClient client, JsonpMapper mapper) {
        super(new VertxTransportHttpClient(client), null, mapper);
    }
}
