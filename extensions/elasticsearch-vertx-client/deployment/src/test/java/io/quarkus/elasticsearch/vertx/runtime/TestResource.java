package io.quarkus.elasticsearch.vertx.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Path("/fruits")
public class TestResource {
    @Inject
    VertxElasticsearchClient restClient;

    @POST
    public void index(Fruit fruit) throws IOException {
        Request request = new Request(
                "PUT",
                "/fruits/_doc/" + fruit.id, Map.of("refresh", "true"), Map.of("Content-Type", "application/json"),
                JsonObject.mapFrom(fruit).toBuffer(), null);
        restClient.performRequest(request);
    }

    @GET
    @Path("/search")
    public List<Fruit> search(@QueryParam("term") String term, @QueryParam("match") String match)
            throws IOException {
        Request request = new Request(
                "GET",
                "/fruits/_search");
        //construct a JSON query like {"query": {"match": {"<term>": "<match"}}
        JsonObject termJson = new JsonObject().put(term, match);
        JsonObject matchJson = new JsonObject().put("match", termJson);
        JsonObject queryJson = new JsonObject().put("query", matchJson);
        request = new Request("GET", "/fruits/_search", Map.of(), Map.of("Content-Type", "application/json"),
                queryJson.toBuffer(), null);
        Response response = restClient.performRequest(request);
        String responseBody = response.getBody().toString();

        JsonObject json = new JsonObject(responseBody);
        JsonArray hits = json.getJsonObject("hits").getJsonArray("hits");
        List<Fruit> results = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            JsonObject hit = hits.getJsonObject(i);
            Fruit fruit = hit.getJsonObject("_source").mapTo(Fruit.class);
            results.add(fruit);
        }
        return results;
    }

    @GET
    @Path("/configured-hosts")
    public String configuredHosts() {
        return ConfigProvider.getConfig().getConfigValue("quarkus.elasticsearch.hosts").getValue();
    }

    public static class Fruit {
        public String id;
        public String name;
        public String color;
    }
}
