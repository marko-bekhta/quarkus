package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.elasticsearch.restclient.vertx.discovery.ElasticsearchNodeDiscovery;
import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscovery;
import io.quarkus.elasticsearch.restclient.vertx.internal.HttpConstants;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractContractTest {

    Vertx vertx;
    VertxElasticsearchClient client;
    URI seedUri;

    void setup(String host, int port) {
        vertx = Vertx.vertx();
        seedUri = URI.create("http://" + host + ":" + port);
        client = VertxElasticsearchClient
                .builder(vertx, seedUri)
                .build();
    }

    @AfterAll
    void teardown() throws Exception {
        if (client != null) {
            try {
                client.performRequest(new Request("DELETE", "/contract-test-index"));
            } catch (Exception ignored) {
            }
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    // --- Basic CRUD ---

    @Test
    @Order(1)
    void clusterInfo() throws Exception {
        Response response = client.performRequest(new Request("GET", "/"));
        assertThat(response.getStatusCode()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).contains("cluster_name");
        assertThat(body).contains("version");
    }

    @Test
    @Order(2)
    void createIndex() throws Exception {
        Request request = new Request("PUT", "/contract-test-index",
                Map.of(), Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}"),
                null);
        Response response = client.performRequest(request);
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("acknowledged");
    }

    @Test
    @Order(3)
    void indexDocument() throws Exception {
        Request request = new Request("PUT", "/contract-test-index/_doc/1",
                Map.of(), Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"title\":\"Test Document\",\"count\":42}"),
                null);
        Response response = client.performRequest(request);
        assertThat(response.getStatusCode()).isIn(200, 201);
        assertThat(response.getBody().toString()).contains("\"_id\":\"1\"");
    }

    @Test
    @Order(4)
    void getDocument() throws Exception {
        Response response = client.performRequest(new Request("GET", "/contract-test-index/_doc/1"));
        assertThat(response.getStatusCode()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).contains("Test Document");
        assertThat(body).contains("42");
    }

    @Test
    @Order(5)
    void searchDocuments() throws Exception {
        client.performRequest(new Request("POST", "/contract-test-index/_refresh"));

        Request request = new Request("POST", "/contract-test-index/_search",
                Map.of(), Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"query\":{\"match_all\":{}}}"),
                null);
        Response response = client.performRequest(request);
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("\"total\"");
    }

    @Test
    @Order(6)
    void deleteDocument() throws Exception {
        Response response = client.performRequest(new Request("DELETE", "/contract-test-index/_doc/1"));
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("\"result\":\"deleted\"");
    }

    @Test
    @Order(7)
    void deleteIndex() throws Exception {
        Response response = client.performRequest(new Request("DELETE", "/contract-test-index"));
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("acknowledged");
    }

    @Test
    @Order(8)
    void notFoundReturns404() throws Exception {
        Response response = client.performRequest(new Request("GET", "/nonexistent-index/_doc/1"));
        assertThat(response.getStatusCode()).isEqualTo(404);
    }

    @Test
    @Order(9)
    void nodesHttpEndpoint() throws Exception {
        Response response = client.performRequest(new Request("GET", "/_nodes/http"));
        assertThat(response.getStatusCode()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).contains("\"nodes\"");
        assertThat(body).contains("\"http\"");
        assertThat(body).contains("\"publish_address\"");
    }

    // --- Async ---

    @Test
    @Order(10)
    void asyncClusterInfo() throws Exception {
        Response response = client.performRequestAsync(new Request("GET", "/"))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("cluster_name");
    }

    @Test
    @Order(11)
    void asyncIndexAndGet() throws Exception {
        Request indexReq = new Request("PUT", "/contract-test-async/_doc/1",
                Map.of(), Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"title\":\"Async Doc\"}"),
                null);
        Response indexResp = client.performRequestAsync(indexReq)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(indexResp.getStatusCode()).isIn(200, 201);

        Response getResp = client.performRequestAsync(new Request("GET", "/contract-test-async/_doc/1"))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(getResp.getStatusCode()).isEqualTo(200);
        assertThat(getResp.getBody().toString()).contains("Async Doc");

        client.performRequest(new Request("DELETE", "/contract-test-async"));
    }

    // --- Bulk NdJSON ---

    @Test
    @Order(12)
    void bulkNdJson() throws Exception {
        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            ndjson.append("{\"index\":{\"_index\":\"contract-test-bulk\",\"_id\":\"").append(i).append("\"}}\n");
            ndjson.append("{\"title\":\"Bulk Doc ").append(i).append("\"}\n");
        }

        Request request = new Request("POST", "/_bulk",
                Map.of(), Map.of("Content-Type", "application/x-ndjson"),
                Buffer.buffer(ndjson.toString()),
                null);
        Response response = client.performRequest(request);
        assertThat(response.getStatusCode()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).contains("\"errors\":false");

        client.performRequest(new Request("POST", "/contract-test-bulk/_refresh"));

        Response searchResp = client.performRequest(new Request("POST", "/contract-test-bulk/_search",
                Map.of(), Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"query\":{\"match_all\":{}}}"),
                null));
        assertThat(searchResp.getBody().toString()).contains("\"total\"");

        client.performRequest(new Request("DELETE", "/contract-test-bulk"));
    }

    // --- Compression ---

    @Test
    @Order(13)
    void compressionRoundTrip() throws Exception {
        VertxElasticsearchClient compressedClient = VertxElasticsearchClient
                .builder(vertx, seedUri)
                .setRequestDispatcher(RequestDispatcher.roundRobin().compressionEnabled(true))
                .build();
        try {
            Response response = compressedClient.performRequest(new Request("GET", "/"));
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBody().toString()).contains("cluster_name");
        } finally {
            compressedClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    // --- Node discovery ---

    @Test
    @Order(14)
    void nodeDiscoveryParsesNodesResponse() throws Exception {
        // Verify node discovery can query _nodes/http and parse the response.
        // In a single-node testcontainer the discovered publish_address is the
        // container-internal IP (not reachable from the host), so we only verify
        // that the discovered nodes carry metadata (name, version, roles).
        //
        // The client no longer exposes its node set -- observability of discovery is a
        // discovery concern, so we tap it the intended way: by decorating the
        // NodeDiscovery SPI and capturing whatever discover() returns.
        AtomicReference<List<Node>> discovered = new AtomicReference<>();
        VertxElasticsearchClient discoveringClient = VertxElasticsearchClient
                .builder(vertx, seedUri)
                .nodeDiscovery(cfg -> cfg
                        .discoveryIntervalMillis(60_000)
                        .nodeDiscoveryFactory(c -> {
                            NodeDiscovery delegate = new ElasticsearchNodeDiscovery(c, 1_000, HttpConstants.Scheme.HTTP);
                            return () -> delegate.discover().onSuccess(discovered::set);
                        }))
                .build();
        try {
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        List<Node> nodes = discovered.get();
                        assertThat(nodes).isNotNull().isNotEmpty();
                        Node node = nodes.get(0);
                        assertThat(node.getName()).isNotNull();
                        assertThat(node.getVersion()).isNotNull();
                        assertThat(node.getRoles()).isNotNull();
                    });
        } finally {
            discoveringClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
