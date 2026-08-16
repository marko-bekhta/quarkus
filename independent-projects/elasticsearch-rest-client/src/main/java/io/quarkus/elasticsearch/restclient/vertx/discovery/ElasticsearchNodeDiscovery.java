package io.quarkus.elasticsearch.restclient.vertx.discovery;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Roles;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;
import io.vertx.core.Future;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * {@link NodeDiscovery} implementation that discovers nodes by querying the Elasticsearch
 * {@code GET /_nodes/http} endpoint and parsing the JSON response with Jackson.
 * <p>
 * Parsing is a single streaming pass over the response driven by a small
 * {@link #forEachField field-dispatch} helper: for each JSON object we visit, the helper
 * hands every field to a handler and skips anything the handler does not claim. This keeps
 * the parse allocation-light (no intermediate document tree) while expressing the walk in
 * terms of "which fields do I care about at this level".
 */
public class ElasticsearchNodeDiscovery implements NodeDiscovery {

    // JsonFactory is thread-safe and stateless; share a single instance across discoveries
    // rather than rebuilding one per request.
    private static final JsonFactory JSON = new JsonFactory();

    private final VertxElasticsearchClient client;
    private final Request discoveryRequest;
    private final HttpConstants.Scheme scheme;

    public ElasticsearchNodeDiscovery(VertxElasticsearchClient client) {
        this(client, 1000, HttpConstants.Scheme.HTTP);
    }

    /**
     * @param client the client used to issue the discovery request
     * @param discoveryServerTimeoutMillis the <em>server-side</em> timeout Elasticsearch applies
     *        while gathering node HTTP info, sent as the {@code timeout} query parameter on
     *        {@code GET /_nodes/http}. This is a hint to the server, not a client-side request
     *        timeout -- the discovery call is otherwise bounded only by the Vert.x
     *        {@code HttpClientOptions} connect/idle settings.
     * @param scheme the URI scheme discovered nodes are addressed with
     */
    public ElasticsearchNodeDiscovery(VertxElasticsearchClient client,
            long discoveryServerTimeoutMillis, HttpConstants.Scheme scheme) {
        this.client = client;
        // Passed to Elasticsearch as the server-side timeout for the nodes-info query, not a
        // client-side request timeout (see the constructor Javadoc).
        this.discoveryRequest = new Request("GET", "/_nodes/http",
                Map.of("timeout", discoveryServerTimeoutMillis + "ms"),
                Map.of(), null, null);
        this.scheme = scheme;
    }

    @Override
    public Future<List<Node>> discover() {
        return client.performRequestAsync(discoveryRequest)
                .map(response -> {
                    try {
                        return parseNodes(response.getBody().getBytes(), scheme);
                    } catch (IOException | JacksonException e) {
                        // IOException covers our own structural checks; JacksonException (unchecked
                        // in Jackson 3) covers malformed/truncated JSON from the parser itself.
                        throw new NodeDiscoveryException(
                                "Failed to parse node discovery response from " + discoveryRequest.getEndpoint(), e);
                    }
                });
    }

    static List<Node> parseNodes(byte[] json, HttpConstants.Scheme scheme) throws IOException {
        List<Node> nodes = new ArrayList<>();
        try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), json)) {
            parser.nextToken(); // position on the root object
            forEachField(parser, (field, p) -> {
                if ("nodes".equals(field)) {
                    readNodesMap(p, nodes, scheme.value);
                    return true;
                }
                return false;
            });
        }
        return nodes;
    }

    // The "nodes" value is an object keyed by node id; we ignore the ids and parse each value.
    private static void readNodesMap(JsonParser parser, List<Node> out, String scheme) throws IOException {
        expect(parser, JsonToken.START_OBJECT);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            parser.nextToken(); // skip the node id, advance to the node object
            Node node = readNode(parser, scheme);
            if (node != null) {
                out.add(node);
            }
        }
    }

    private static Node readNode(JsonParser parser, String scheme) throws IOException {
        NodeInfo info = new NodeInfo();
        forEachField(parser, (field, p) -> {
            switch (field) {
                case "name" -> info.name = p.getString();
                case "version" -> info.version = p.getString();
                case "roles" -> info.roles = readStringArray(p);
                case "attributes" -> info.attributes = readAttributes(p);
                case "http" -> readHttp(p, info);
                default -> {
                    return false;
                }
            }
            return true;
        });
        return info.toNode(scheme);
    }

    private static void readHttp(JsonParser parser, NodeInfo info) throws IOException {
        info.hasHttp = true;
        forEachField(parser, (field, p) -> {
            switch (field) {
                case "publish_address" -> info.publishAddress = p.getString();
                case "bound_address" -> info.boundAddresses = readStringArray(p);
                default -> {
                    return false;
                }
            }
            return true;
        });
    }

    private static Map<String, List<String>> readAttributes(JsonParser parser) throws IOException {
        Map<String, String> raw = new HashMap<>();
        forEachField(parser, (field, p) -> {
            JsonToken token = p.currentToken();
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                return false; // ignore nested attribute structures
            }
            if (raw.containsKey(field)) {
                throw new IOException("Duplicate attribute key: " + field);
            }
            raw.put(field, p.getString());
            return true;
        });
        return unflattenAttributes(raw);
    }

    // Unflatten multi-valued attributes: key.0, key.1, ... -> key: [val0, val1]
    private static Map<String, List<String>> unflattenAttributes(Map<String, String> raw) {
        Map<String, List<String>> result = new HashMap<>();
        Map<String, Map<Integer, String>> multiValued = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            int dotIdx = key.lastIndexOf('.');
            if (dotIdx > 0) {
                String suffix = key.substring(dotIdx + 1);
                try {
                    int index = Integer.parseInt(suffix);
                    String baseKey = key.substring(0, dotIdx);
                    multiValued.computeIfAbsent(baseKey, k -> new HashMap<>())
                            .put(index, entry.getValue());
                    continue;
                } catch (NumberFormatException e) {
                    // not a multi-valued attribute
                }
            }
            result.put(key, List.of(entry.getValue()));
        }

        for (Map.Entry<String, Map<Integer, String>> entry : multiValued.entrySet()) {
            Map<Integer, String> indexed = entry.getValue();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < indexed.size(); i++) {
                String val = indexed.get(i);
                if (val == null) {
                    break;
                }
                values.add(val);
            }
            result.put(entry.getKey(), List.copyOf(values));
        }

        return result.isEmpty() ? null : result;
    }

    private static List<String> readStringArray(JsonParser parser) throws IOException {
        expect(parser, JsonToken.START_ARRAY);
        List<String> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(parser.getString());
        }
        return values;
    }

    /**
     * Walks a JSON object, handing each field (with the parser positioned on the field's
     * value) to {@code handler}. Whatever the handler does not consume -- signalled by a
     * {@code false} return -- is skipped. The parser must be positioned on the object's
     * {@code START_OBJECT} token.
     */
    private static void forEachField(JsonParser parser, FieldHandler handler) throws IOException {
        expect(parser, JsonToken.START_OBJECT);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken(); // advance onto the field's value
            if (!handler.handle(field, parser)) {
                parser.skipChildren();
            }
        }
    }

    private static void expect(JsonParser parser, JsonToken token) throws IOException {
        if (parser.currentToken() != token) {
            throw new IOException("Expected " + token + " but got " + parser.currentToken());
        }
    }

    @FunctionalInterface
    private interface FieldHandler {
        /**
         * @return {@code true} if the field's value was consumed; {@code false} to have the
         *         caller skip it (including any nested children).
         */
        boolean handle(String field, JsonParser parser) throws IOException;
    }

    // Mutable accumulator for a single node's fields, built up across the streaming walk.
    private static final class NodeInfo {
        String name;
        String version;
        List<String> roles;
        Map<String, List<String>> attributes;
        String publishAddress;
        List<String> boundAddresses;
        boolean hasHttp;

        Node toNode(String scheme) throws IOException {
            // A node without an HTTP publish address is not reachable over REST; drop it.
            if (!hasHttp || publishAddress == null) {
                return null;
            }
            URI host = parsePublishAddress(publishAddress, scheme);
            Set<URI> boundHosts = null;
            if (boundAddresses != null) {
                boundHosts = new HashSet<>();
                for (String addr : boundAddresses) {
                    boundHosts.add(parseSimpleAddress(addr, scheme));
                }
            }
            Roles roleSet = roles != null ? new Roles(new HashSet<>(roles)) : null;
            return new NodeImpl(host, boundHosts, name, version, roleSet, attributes);
        }
    }

    private static URI parsePublishAddress(String address, String scheme) throws IOException {
        if (address.contains("/")) {
            // From ES 7 on, publish_address carries a "hostname/ip:port" pair; keep the
            // hostname portion and take the port from the "ip:port" part after the slash.
            String[] parts = address.split("/");
            String hostname = parts[0];
            int port = parsePort(parts[1]);
            return parseSimpleAddress(hostname + ":" + port, scheme);
        }
        return parseSimpleAddress(address, scheme);
    }

    private static URI parseSimpleAddress(String address, String scheme) throws IOException {
        // address is already "host:port" (IPv6 hosts bracketed, e.g. "[::1]:9200"); we only
        // prepend the scheme, letting URI parse the (possibly bracketed-IPv6) authority. URI
        // does NOT reject a non-numeric port -- it silently falls back to a registry-based
        // authority (host == null, port == -1) -- so we validate the parsed result explicitly.
        URI uri;
        try {
            uri = URI.create(scheme + "://" + address);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed node address: " + address, e);
        }
        if (uri.getHost() == null || uri.getPort() < 0) {
            throw new IOException("Malformed node address: " + address);
        }
        return uri;
    }

    private static int parsePort(String ipPort) throws IOException {
        try {
            if (ipPort.startsWith("[")) {
                int closeBracket = ipPort.lastIndexOf(']');
                return Integer.parseInt(ipPort.substring(closeBracket + 2));
            }
            return Integer.parseInt(ipPort.substring(ipPort.lastIndexOf(':') + 1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IOException("Malformed node address: " + ipPort, e);
        }
    }
}
