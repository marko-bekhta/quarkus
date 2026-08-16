package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

class ResponseTest {

    @Test
    void basicProperties() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Content-Type", "application/json");
        Buffer body = Buffer.buffer("{\"ok\":true}");
        URI node = URI.create("http://localhost:9200");

        Response response = new Response(200, "OK", headers, body, node, "GET");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getStatusMessage()).isEqualTo("OK");
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(response.getBody().toString()).isEqualTo("{\"ok\":true}");
        assertThat(response.getNode()).isEqualTo(node);
        assertThat(response.getRequestMethod()).isEqualTo("GET");
    }

    @Test
    void getHeaderReturnsNullForMissing() {
        Response response = new Response(200, "OK", MultiMap.caseInsensitiveMultiMap(),
                null, URI.create("http://localhost:9200"), "GET");
        assertThat(response.getHeader("X-Missing")).isNull();
    }

    @Test
    void getHeaderReturnsNullWhenNoHeaders() {
        Response response = new Response(200, "OK", null, null,
                URI.create("http://localhost:9200"), "GET");
        assertThat(response.getHeader("X-Missing")).isNull();
    }

    @Test
    void warningsEmptyWhenNoHeaders() {
        Response response = new Response(200, "OK", null, null,
                URI.create("http://localhost:9200"), "GET");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void warningsEmptyWhenNoWarningHeaders() {
        Response response = new Response(200, "OK", MultiMap.caseInsensitiveMultiMap(),
                null, URI.create("http://localhost:9200"), "GET");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void warningsParseStandardEsFormat() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "299 Elasticsearch-8.0.0 \"deprecation message\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("deprecation message");
    }

    @Test
    void warningsParseWithDateSuffix() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning",
                "299 Elasticsearch-8.0.0 \"deprecation message\" \"Mon, 01 Jan 2024 00:00:00 GMT\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("deprecation message");
    }

    @Test
    void warningsParseOpenSearchFormat() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "299 OpenSearch-3.0.0-abc123 \"deprecation message\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("deprecation message");
    }

    @Test
    void warningsUnknownWarnCodeReturnedAsIs() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "300 Elasticsearch-8.0.0 \"another warning\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        // Only warn-code 299 is a deprecation warning; anything else is passed through verbatim.
        assertThat(response.getWarnings()).containsExactly("300 Elasticsearch-8.0.0 \"another warning\"");
    }

    @Test
    void warningsNonEsFormatReturnedAsIs() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "199 Miscellaneous warning");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("199 Miscellaneous warning");
    }

    @Test
    void warningsMultipleHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "299 Elasticsearch-8.0.0 \"first warning\"");
        headers.add("Warning", "299 Elasticsearch-8.0.0 \"second warning\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("first warning", "second warning");
    }

    @Test
    void warningsCommaSeparatedInSingleHeader() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning",
                "299 Elasticsearch-8.0.0 \"first warning\", 299 Elasticsearch-8.0.0 \"second warning\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("first warning", "second warning");
    }

    @Test
    void warningsEscapedQuotes() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "299 Elasticsearch-8.0.0 \"message with \\\"escaped\\\" quotes\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("message with \"escaped\" quotes");
    }

    @Test
    void warningsEscapedBackslash() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning", "299 Elasticsearch-8.0.0 \"path is C:\\\\data\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("path is C:\\data");
    }

    @Test
    void warningsCommaSeparatedWithDateSuffix() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Warning",
                "299 Elasticsearch-8.0.0 \"first\" \"Mon, 01 Jan 2024 00:00:00 GMT\", "
                        + "299 Elasticsearch-8.0.0 \"second\"");

        Response response = new Response(200, "OK", headers, null,
                URI.create("http://localhost:9200"), "GET");

        assertThat(response.getWarnings()).containsExactly("first", "second");
    }
}
