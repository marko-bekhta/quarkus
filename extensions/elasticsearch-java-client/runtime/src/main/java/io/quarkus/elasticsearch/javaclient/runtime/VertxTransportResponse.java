package io.quarkus.elasticsearch.javaclient.runtime;

import java.util.List;

import co.elastic.clients.transport.http.TransportHttpClient;
import co.elastic.clients.util.BinaryData;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.vertx.core.buffer.Buffer;

final class VertxTransportResponse implements TransportHttpClient.Response {
    private final Response response;
    private final BinaryData body;

    VertxTransportResponse(Response response) {
        this.response = response;
        Buffer buffer = response.getBody();
        this.body = buffer == null || buffer.length() == 0 ? null
                : BinaryData.of(buffer.getBytes(), response.getHeader("Content-Type"));
    }

    @Override
    public TransportHttpClient.Node node() {
        return new TransportHttpClient.Node(response.getNode());
    }

    @Override
    public int statusCode() {
        return response.getStatusCode();
    }

    @Override
    public String header(String name) {
        return response.getHeader(name);
    }

    @Override
    public List<String> headers(String name) {
        return response.getHeaders() == null ? List.of() : response.getHeaders().getAll(name);
    }

    @Override
    public BinaryData body() {
        return body;
    }

    @Override
    public Object originalResponse() {
        return response;
    }

    @Override
    public void close() {
        // The low-level client consumed the response before delivery. The owned byte array remains
        // readable after closure, including through TransportException.response().body().
    }
}
