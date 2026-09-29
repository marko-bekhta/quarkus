package io.quarkus.elasticsearch.restclient.vertx;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * An HTTP request to send to an Elasticsearch cluster, consisting of an HTTP method,
 * endpoint path, optional query parameters, headers, body, and a warnings handler.
 * Headers are mutable. A {@link MultiMap} supplied to {@link Request} is kept by reference;
 * callers must not change it while a request is in flight, including during retries.
 */
public final class Request {

    private final String method;
    private final String endpoint;
    private final Map<String, String> parameters;
    private volatile MultiMap headers;
    private final Buffer body;
    private final WarningsHandler warningsHandler;
    private final String queryString;

    public Request(String method, String endpoint) {
        this(method, endpoint, Collections.emptyMap(), Collections.emptyMap(), null, null);
    }

    public Request(String method, String endpoint, Map<String, String> parameters,
            Map<String, String> headers, Buffer body, WarningsHandler warningsHandler) {
        this(method, endpoint, parameters, toMultiMap(headers), body, warningsHandler);
    }

    public Request(String method, String endpoint, Map<String, String> parameters,
            MultiMap headers, Buffer body, WarningsHandler warningsHandler) {
        this.method = Objects.requireNonNull(method, "method");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.parameters = parameters == null ? Collections.emptyMap() : Map.copyOf(parameters);
        this.headers = headers;
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

    /**
     * Returns the live, case-insensitive headers. A header map is created on first access if needed.
     * Callers must not modify it while the request is in flight, including during retries.
     */
    public MultiMap getHeaders() {
        if (headers == null) {
            headers = MultiMap.caseInsensitiveMultiMap();
        }
        return headers;
    }

    /**
     * Returns whether this request currently has any headers without creating a header map.
     */
    public boolean hasHeaders() {
        MultiMap currentHeaders = headers;
        return currentHeaders != null && !currentHeaders.isEmpty();
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

    private static MultiMap toMultiMap(Map<String, String> headers) {
        return headers == null || headers.isEmpty() ? null : MultiMap.caseInsensitiveMultiMap().addAll(headers);
    }
}
