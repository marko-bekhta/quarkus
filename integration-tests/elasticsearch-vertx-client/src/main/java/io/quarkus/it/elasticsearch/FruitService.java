package io.quarkus.it.elasticsearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class FruitService {
    @Inject
    VertxElasticsearchClient restClient;

    public void index(Fruit fruit) throws IOException {
        Request request = new Request(
                "PUT",
                "/fruits/_doc/" + fruit.id, Map.of(), Map.of("Content-Type", "application/json"),
                JsonObject.mapFrom(fruit).toBuffer(), null);
        restClient.performRequest(request);
    }

    public Fruit get(String id) throws IOException {
        Request request = new Request(
                "GET",
                "/fruits/_doc/" + id);
        Response response = restClient.performRequest(request);
        String responseBody = response.getBody().toString();
        JsonObject json = new JsonObject(responseBody);
        return json.getJsonObject("_source").mapTo(Fruit.class);
    }

    public List<Fruit> searchByColor(String color) throws IOException {
        return search("color", color);
    }

    public List<Fruit> searchByName(String name) throws IOException {
        return search("name", name);
    }

    private List<Fruit> search(String term, String match) throws IOException {
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

    public void index(List<Fruit> list) throws IOException {

        var entityList = new ArrayList<JsonObject>();

        for (var fruit : list) {

            entityList.add(new JsonObject().put("index", new JsonObject()
                    .put("_index", "fruits").put("_id", fruit.id)));
            entityList.add(JsonObject.mapFrom(fruit));
        }

        Request request = new Request(
                "POST", "/fruits/_bulk", Map.of("pretty", "true"), Map.of("Content-Type", "application/x-ndjson"),
                Buffer.buffer(toNdJsonString(entityList)), null);
        restClient.performRequest(request);
    }

    public void delete(List<String> identityList) throws IOException {

        var entityList = new ArrayList<JsonObject>();

        for (var id : identityList) {
            entityList.add(new JsonObject().put("delete",
                    new JsonObject().put("_index", "fruits").put("_id", id)));
        }

        Request request = new Request(
                "POST", "/fruits/_bulk", Map.of("pretty", "true"), Map.of("Content-Type", "application/x-ndjson"),
                Buffer.buffer(toNdJsonString(entityList)), null);
        restClient.performRequest(request);
    }

    private static String toNdJsonString(List<JsonObject> objects) {
        return objects.stream()
                .map(JsonObject::encode)
                .collect(Collectors.joining("\n", "", "\n"));
    }
}
