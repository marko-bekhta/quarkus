package io.quarkus.it.elasticsearch.java;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/fruits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FruitResource {
    @Inject
    FruitService fruitService;

    @GET
    @Path("/apache-classes")
    public List<String> apacheClasses() {
        List<String> present = new ArrayList<>();
        for (String name : List.of("org.apache.http.client.HttpClient", "org.apache.http.HttpHost",
                "org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient", "org.apache.hc.core5.http.HttpHost",
                "org.elasticsearch.client.RestClient", "org.elasticsearch.client.sniff.Sniffer",
                "co.elastic.clients.transport.rest5_client.low_level.Rest5Client")) {
            try {
                Class.forName(name, false, Thread.currentThread().getContextClassLoader());
                present.add(name);
            } catch (ClassNotFoundException expected) {
                // The production application must not contain Apache Elasticsearch transports.
            }
        }
        return present;
    }

    @POST
    public Response index(Fruit fruit) throws IOException {
        if (fruit.id == null) {
            fruit.id = UUID.randomUUID().toString();
        }
        fruitService.index(fruit);
        return Response.created(URI.create("/fruits/" + fruit.id)).build();
    }

    @POST
    @Path("/async")
    public CompletionStage<Response> indexAsync(Fruit fruit) {
        if (fruit.id == null) {
            fruit.id = UUID.randomUUID().toString();
        }
        return fruitService.indexAsync(fruit)
                .thenApply(ignored -> Response.created(URI.create("/fruits/async/" + fruit.id)).build());
    }

    @GET
    @Path("/async/{id}")
    public CompletionStage<Fruit> getAsync(@PathParam("id") String id) {
        return fruitService.getAsync(id);
    }

    @GET
    @Path("/{id}")
    public Fruit get(@PathParam("id") String id) throws IOException {
        return fruitService.get(id);
    }

    @GET
    @Path("/search")
    public List<Fruit> search(@QueryParam("name") String name, @QueryParam("color") String color) throws IOException {
        if (name != null) {
            return fruitService.searchByName(name);
        } else if (color != null) {
            return fruitService.searchByColor(color);
        } else {
            throw new BadRequestException("Should provide name or color query parameter");
        }
    }

    // This is just for tests, as it's bad practice to allow REST API callers
    // to just inject whatever JSON they like into your Elasticsearch requests.
    @GET
    @Path("/search/unsafe")
    public List<Fruit> searchUnsafe(@QueryParam("json") String json) throws IOException {
        return fruitService.searchWithJson(json);
    }

    @Path("bulk")
    @DELETE
    public Response delete(List<String> identityList) throws IOException {
        fruitService.delete(identityList);
        return Response.ok().build();
    }

    @Path("bulk")
    @POST
    public Response index(List<Fruit> list) throws IOException {
        fruitService.index(list);
        return Response.ok().build();
    }

}
