package io.quarkus.elasticsearch.restclient.vertx;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.buffer.Buffer;

/**
 * An HTTP request to send to an Elasticsearch cluster, consisting of an HTTP method,
 * endpoint path, optional query parameters, headers, body, and a warnings handler.
 */
public final class Request {

    private final String method;
    private final String endpoint;
    private final Map<String, String> parameters;
    private final Map<String, String> headers;
    private final Buffer body;
    private final WarningsHandler warningsHandler;
    private final String queryString;

    public Request(String method, String endpoint) {
        this(method, endpoint, Collections.emptyMap(), Collections.emptyMap(), null, null);
    }

    public Request(String method, String endpoint, Map<String, String> parameters,
            Map<String, String> headers, Buffer body, WarningsHandler warningsHandler) {
        this.method = Objects.requireNonNull(method, "method");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.parameters = parameters == null ? Collections.emptyMap() : Map.copyOf(parameters);
        this.headers = headers == null ? Collections.emptyMap() : Map.copyOf(headers);
        this.body = body;
        this.warningsHandler = warningsHandler;
        if (!this.parameters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> param : this.parameters.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
            this.queryString = sb.toString();
        } else {
            this.queryString = "";
        }
    }

    public String getMethod() {
        return method;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Buffer getBody() {
        return body;
    }

    public WarningsHandler getWarningsHandler() {
        return warningsHandler;
    }

    public String getQueryString() {
        return queryString;
    }

    @Override
    public String toString() {
        return "Request[" + method + " " + endpoint + "]";
    }
}
