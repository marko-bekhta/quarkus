package io.quarkus.elasticsearch.restclient.vertx;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * An HTTP response received from an Elasticsearch node, including the status code,
 * headers, body, and parsed deprecation warnings.
 */
public final class Response {

    // Deprecation warnings use RFC 7234 warn-code 299 ("miscellaneous persistent warning").
    // Both Elasticsearch and OpenSearch emit '299 <agent>-<version>-<hash> "<message>"', so we key
    // on the warn-code plus the quoted-string, not on any vendor-specific agent token.
    private static final String DEPRECATION_WARN_CODE = "299 ";

    private final int statusCode;
    private final String statusMessage;
    private final MultiMap headers;
    private final Buffer body;
    private final URI node;
    private final String requestMethod;

    public Response(int statusCode, String statusMessage, MultiMap headers,
            Buffer body, URI node, String requestMethod) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.headers = headers;
        this.body = body;
        this.node = node;
        this.requestMethod = requestMethod;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getHeader(String name) {
        return headers != null ? headers.get(name) : null;
    }

    public MultiMap getHeaders() {
        return headers;
    }

    public Buffer getBody() {
        return body;
    }

    public URI getNode() {
        return node;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public List<String> getWarnings() {
        if (headers == null) {
            return Collections.emptyList();
        }
        List<String> rawWarnings = headers.getAll(HttpConstants.Headers.WARNING);
        if (rawWarnings == null || rawWarnings.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(rawWarnings.size());
        for (String raw : rawWarnings) {
            parseWarningHeader(raw, result);
        }
        return Collections.unmodifiableList(result);
    }

    private static void parseWarningHeader(String header, List<String> result) {
        int len = header.length();
        int pos = 0;
        while (pos < len) {
            // Skip leading whitespace and commas between warnings
            while (pos < len && (header.charAt(pos) == ' ' || header.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= len) {
                break;
            }
            boolean isDeprecation = header.startsWith(DEPRECATION_WARN_CODE, pos);
            // Find the first quoted-string
            int quoteStart = header.indexOf('"', pos);
            if (quoteStart < 0) {
                // No quoted string; add remainder as-is for non-deprecation warnings
                if (!isDeprecation) {
                    result.add(header.substring(pos).trim());
                }
                break;
            }
            // Extract the quoted-string content, handling escaped characters
            int cursor = quoteStart + 1;
            StringBuilder sb = new StringBuilder();
            boolean closed = false;
            while (cursor < len) {
                char c = header.charAt(cursor);
                if (c == '\\' && cursor + 1 < len) {
                    sb.append(header.charAt(cursor + 1));
                    cursor += 2;
                } else if (c == '"') {
                    closed = true;
                    cursor++;
                    break;
                } else {
                    sb.append(c);
                    cursor++;
                }
            }
            if (isDeprecation && closed) {
                result.add(sb.toString());
            } else if (!isDeprecation) {
                result.add(header.substring(pos, Math.min(cursor, len)).trim());
            }
            // Skip past any remaining quoted-strings (e.g. the optional date) and
            // advance to the next comma separator or end of header
            pos = cursor;
            while (pos < len && header.charAt(pos) != ',') {
                if (header.charAt(pos) == '"') {
                    pos++;
                    while (pos < len) {
                        char c = header.charAt(pos);
                        if (c == '\\' && pos + 1 < len) {
                            pos += 2;
                        } else if (c == '"') {
                            pos++;
                            break;
                        } else {
                            pos++;
                        }
                    }
                } else {
                    pos++;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "Response{statusCode=" + statusCode + ", node=" + node + '}';
    }
}
