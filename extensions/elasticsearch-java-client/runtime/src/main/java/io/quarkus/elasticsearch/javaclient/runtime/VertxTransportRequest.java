package io.quarkus.elasticsearch.javaclient.runtime;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import co.elastic.clients.transport.TransportOptions;
import co.elastic.clients.transport.http.TransportHttpClient;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

final class VertxTransportRequest {
    private VertxTransportRequest() {
    }

    static Request convert(TransportHttpClient.Node node, TransportHttpClient.Request request,
            TransportOptions options) {
        if (node != null) {
            throw new IllegalArgumentException("Node selection is managed by the Vert.x Elasticsearch client");
        }
        Map<String, String> parameters = new HashMap<>(request.queryParams());
        parameters.putAll(options.queryParameters());
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        request.headers().forEach(headers::set);
        for (Map.Entry<String, String> entry : options.headers()) {
            headers.remove(entry.getKey());
        }
        for (Map.Entry<String, String> entry : options.headers()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        Buffer body = null;
        if (request.body() != null) {
            body = Buffer.buffer();
            for (ByteBuffer part : request.body()) {
                ByteBuffer copy = part.duplicate();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                body.appendBytes(bytes);
            }
        }
        WarningsHandler warningsHandler = options.onWarnings() == null ? null
                : warnings -> !warnings.isEmpty() && options.onWarnings().apply(warnings);
        return new Request(request.method(), request.path(), parameters, headers, body, warningsHandler);
    }

}
