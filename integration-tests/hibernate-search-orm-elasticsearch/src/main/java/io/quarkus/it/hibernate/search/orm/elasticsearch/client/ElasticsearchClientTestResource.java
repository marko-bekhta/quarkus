package io.quarkus.it.hibernate.search.orm.elasticsearch.client;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

@Path("/test/elasticsearch-client")
public class ElasticsearchClientTestResource {

    @Inject
    Vertx vertx;

    @GET
    @Path("/connection")
    @Produces(MediaType.TEXT_PLAIN)
    public String testConnection() throws IOException {
        VertxElasticsearchClient client = createClient(false);
        try {
            checkStatus(client.performRequest(new Request("GET", "/")), 200);
            return "OK";
        } finally {
            close(client);
        }
    }

    @GET
    @Path("/full-cycle")
    @Produces(MediaType.TEXT_PLAIN)
    public String testFullCycle() throws IOException {
        VertxElasticsearchClient client = createClient(false);
        try {
            client.performRequest(new Request("DELETE", "/books"));
            checkStatus(send(client, "PUT", "/books", """
                    {"settings":{"number_of_shards":1},
                     "mappings":{"properties":{"title":{"type":"text"},"author":{"type":"text"}}}}
                    """), 200);
            checkStatus(send(client, "POST", "/books/_doc/1", """
                    {"title":"4 3 2 1","author":"Auster"}
                    """), 201);
            checkStatus(send(client, "POST", "/books/_doc/2", """
                    {"title":"Avenue of mysteries","author":"Irving"}
                    """), 201);
            Response response = send(client, "POST", "/books/_search", """
                    {"query":{"simple_query_string":{"query":"Irving"}}}
                    """);
            String content = response.getBody().toString();
            checkStatus(response, 200);
            if (!content.contains("mysteries")) {
                throw new IllegalStateException("Content should contain mysteries but is: " + content);
            }
            return "OK";
        } finally {
            close(client);
        }
    }

    @GET
    @Path("/sniffer")
    @Produces(MediaType.TEXT_PLAIN)
    public String testSniffer() throws IOException, InterruptedException {
        VertxElasticsearchClient client = createClient(true);
        try {
            TimeUnit.MILLISECONDS.sleep(20);
            checkStatus(client.performRequest(new Request("GET", "/")), 200);
            return "OK";
        } finally {
            close(client);
        }
    }

    private VertxElasticsearchClient createClient(boolean discovery) {
        var builder = VertxElasticsearchClient.builder(vertx, URI.create("http://localhost:9200"));
        if (discovery) {
            builder.nodeDiscovery(config -> config.discoveryIntervalMillis(5));
        }
        return builder.build();
    }

    private static Response send(VertxElasticsearchClient client, String method, String path, String json) throws IOException {
        return client.performRequest(new Request(method, path, path.contains("/_doc/") ? Map.of("refresh", "true") : Map.of(),
                Map.of("Content-Type", "application/json"), Buffer.buffer(json), null));
    }

    private static void close(VertxElasticsearchClient client) {
        client.close().toCompletionStage().toCompletableFuture().join();
    }

    private static void checkStatus(Response response, int expected) {
        if (response.getStatusCode() != expected) {
            throw new IllegalStateException("Status should have been " + expected + " but is: "
                    + response.getStatusCode());
        }
    }
}
