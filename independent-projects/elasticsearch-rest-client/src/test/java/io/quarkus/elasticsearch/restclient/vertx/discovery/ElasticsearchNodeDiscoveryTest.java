package io.quarkus.elasticsearch.restclient.vertx.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

class ElasticsearchNodeDiscoveryTest {

    static Vertx vertx;

    @BeforeAll
    static void setup() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void teardown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void discoverWrapsMalformedResponseInNodeDiscoveryException() throws Exception {
        HttpServer server = startServer(req -> req.response().setStatusCode(200).end("this is not json"));
        VertxElasticsearchClient client = VertxElasticsearchClient
                .builder(vertx, URI.create("http://localhost:" + server.actualPort()))
                .build();
        try {
            ElasticsearchNodeDiscovery discovery = new ElasticsearchNodeDiscovery(client);

            assertThatThrownBy(() -> discovery.discover()
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(NodeDiscoveryException.class);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static HttpServer startServer(Handler<HttpServerRequest> handler) throws Exception {
        return vertx.createHttpServer()
                .requestHandler(handler)
                .listen(0)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void parseEs817Response() throws Exception {
        byte[] json = loadFixture("es_8.17.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getName()).isNotNull();
        assertThat(node.getVersion()).isEqualTo("8.17.0");
        assertThat(node.getHost()).isNotNull();
        assertThat(node.getHost().getScheme()).isEqualTo("http");
        assertThat(node.getHost().getPort()).isEqualTo(9200);

        assertThat(node.getRoles()).isNotNull();
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("ingest")).isTrue();
        assertThat(node.getRoles().has("data_hot")).isTrue();
        assertThat(node.getRoles().has("data_cold")).isTrue();
        assertThat(node.getRoles().has("data_warm")).isTrue();
        assertThat(node.getRoles().has("data_content")).isTrue();
        assertThat(node.getRoles().has("data_frozen")).isTrue();
        assertThat(node.getRoles().anyMatch(role -> role.startsWith("data_"))).isTrue();
    }

    @Test
    void parseOpensearch219Response() throws Exception {
        byte[] json = loadFixture("opensearch_2.19.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getName()).isNotNull();
        assertThat(node.getVersion()).isEqualTo("2.19.0");
        assertThat(node.getHost().getScheme()).isEqualTo("http");
        assertThat(node.getHost().getPort()).isEqualTo(9200);

        assertThat(node.getRoles()).isNotNull();
        // OpenSearch uses cluster_manager instead of master
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("ingest")).isTrue();
    }

    @Test
    void parseEs717Response() throws Exception {
        byte[] json = loadFixture("es_7.17.27_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getVersion()).isEqualTo("7.17.27");
        assertThat(node.getHost().getScheme()).isEqualTo("http");
        assertThat(node.getHost().getPort()).isEqualTo(9200);
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("ingest")).isTrue();
    }

    @Test
    void parseEs900Response() throws Exception {
        byte[] json = loadFixture("es_9.0.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getVersion()).isEqualTo("9.0.0");
        assertThat(node.getHost().getPort()).isEqualTo(9200);
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("data_hot")).isTrue();
        assertThat(node.getRoles().has("data_cold")).isTrue();
    }

    @Test
    void parseEs940Response() throws Exception {
        byte[] json = loadFixture("es_9.4.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getVersion()).isEqualTo("9.4.0");
        assertThat(node.getHost().getPort()).isEqualTo(9200);
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().anyMatch(role -> role.startsWith("data_"))).isTrue();
    }

    @Test
    void parseOpensearch139Response() throws Exception {
        byte[] json = loadFixture("opensearch_1.3.19_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getVersion()).isEqualTo("1.3.19");
        assertThat(node.getHost().getPort()).isEqualTo(9200);
        // OpenSearch 1.x uses "master" role (not cluster_manager)
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("ingest")).isTrue();
    }

    @Test
    void parseOpensearch300Response() throws Exception {
        byte[] json = loadFixture("opensearch_3.0.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        Node node = nodes.get(0);
        assertThat(node.getVersion()).isEqualTo("3.0.0");
        assertThat(node.getHost().getPort()).isEqualTo(9200);
        // OpenSearch 3.x uses cluster_manager role
        assertThat(node.getRoles().hasAny("master", "cluster_manager")).isTrue();
        assertThat(node.getRoles().has("data")).isTrue();
        assertThat(node.getRoles().has("ingest")).isTrue();
    }

    @Test
    void httpsSchemeApplied() throws Exception {
        byte[] json = loadFixture("es_8.17.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTPS);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getHost().getScheme()).isEqualTo("https");
    }

    @Test
    void publishAddressWithCnameFormat() throws Exception {
        String json = buildSingleNodeJson("es-node.example.com/10.0.0.1:9200");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getHost())
                .isEqualTo(URI.create("http://es-node.example.com:9200"));
    }

    @Test
    void publishAddressSimpleIpPort() throws Exception {
        String json = buildSingleNodeJson("10.0.0.1:9200");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getHost())
                .isEqualTo(URI.create("http://10.0.0.1:9200"));
    }

    @Test
    void ipv6PublishAddress() throws Exception {
        String json = buildSingleNodeJson("[::1]:9200");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getHost())
                .isEqualTo(URI.create("http://[::1]:9200"));
    }

    @Test
    void nodeWithoutHttpSectionSkipped() throws Exception {
        String json = """
                {
                  "nodes": {
                    "node1": {
                      "name": "no-http-node",
                      "version": "8.17.0",
                      "roles": ["master"]
                    }
                  }
                }
                """;
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);
        assertThat(nodes).isEmpty();
    }

    @Test
    void emptyNodesObject() throws Exception {
        String json = """
                {
                  "_nodes": {"total": 0, "successful": 0, "failed": 0},
                  "cluster_name": "test",
                  "nodes": {}
                }
                """;
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);
        assertThat(nodes).isEmpty();
    }

    @Test
    void unknownFieldsSkipped() throws Exception {
        String json = """
                {
                  "_nodes": {"total": 1, "successful": 1},
                  "cluster_name": "test",
                  "some_future_field": {"nested": true},
                  "nodes": {
                    "id1": {
                      "name": "n1",
                      "version": "99.0.0",
                      "roles": ["data"],
                      "unknown_section": {"a": 1},
                      "http": {
                        "publish_address": "10.0.0.1:9200",
                        "unknown_http_field": 42
                      }
                    }
                  }
                }
                """;
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getVersion()).isEqualTo("99.0.0");
    }

    @Test
    void multiValuedAttributesUnflattened() throws Exception {
        String json = """
                {
                  "nodes": {
                    "id1": {
                      "name": "n1",
                      "version": "8.17.0",
                      "roles": ["data"],
                      "attributes": {
                        "zone.0": "us-east-1a",
                        "zone.1": "us-east-1b",
                        "single": "value"
                      },
                      "http": {
                        "publish_address": "10.0.0.1:9200"
                      }
                    }
                  }
                }
                """;
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getAttributes()).containsKey("zone");
        assertThat(nodes.get(0).getAttributes().get("zone"))
                .containsExactly("us-east-1a", "us-east-1b");
        assertThat(nodes.get(0).getAttributes().get("single"))
                .containsExactly("value");
    }

    @Test
    void malformedPublishAddressPortRejected() {
        // URI.create silently accepts a non-numeric port (host == null, port == -1); parseNodes
        // must reject it as an IOException so discover() wraps it in NodeDiscoveryException.
        String json = buildSingleNodeJson("10.0.0.1:not-a-port");
        assertThatThrownBy(() -> ElasticsearchNodeDiscovery.parseNodes(json.getBytes(),
                HttpConstants.Scheme.HTTP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed node address");
    }

    @Test
    void boundAddressesParsed() throws Exception {
        byte[] json = loadFixture("es_8.17.0_nodes_http.json");
        List<Node> nodes = ElasticsearchNodeDiscovery.parseNodes(json,
                HttpConstants.Scheme.HTTP);

        assertThat(nodes.get(0).getBoundHosts()).isNotNull();
        assertThat(nodes.get(0).getBoundHosts()).isNotEmpty();
    }

    private static byte[] loadFixture(String name) throws IOException {
        try (InputStream is = ElasticsearchNodeDiscoveryTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Fixture not found: " + name);
            }
            return is.readAllBytes();
        }
    }

    private static String buildSingleNodeJson(String publishAddress) {
        return """
                {
                  "nodes": {
                    "id1": {
                      "name": "test-node",
                      "version": "8.17.0",
                      "roles": ["data", "master"],
                      "http": {
                        "publish_address": "%s"
                      }
                    }
                  }
                }
                """.formatted(publishAddress);
    }
}
