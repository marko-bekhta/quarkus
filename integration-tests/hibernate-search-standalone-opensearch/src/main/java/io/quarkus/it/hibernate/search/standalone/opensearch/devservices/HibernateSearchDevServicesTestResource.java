package io.quarkus.it.hibernate.search.standalone.opensearch.devservices;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;

@Path("/test/dev-services")
public class HibernateSearchDevServicesTestResource {

    @Inject
    SearchMapping searchMapping;

    @ConfigProperty(name = "quarkus.hibernate-search-standalone.elasticsearch.hosts")
    String configuredHosts;

    @GET
    @Path("/hosts")
    @Transactional
    public String hosts() {
        return configuredHosts;
    }

    @PUT
    @Path("/init-data")
    @Transactional
    public void initData() {
        try (var searchSession = searchMapping.createSession()) {
            IndexedEntity entity = new IndexedEntity(1, "John Irving");
            searchSession.indexingPlan().add(entity);
        }
        searchMapping.scope(IndexedEntity.class).workspace().refresh();
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public long count() {
        try (var searchSession = searchMapping.createSession()) {
            return searchSession.search(IndexedEntity.class)
                    .where(f -> f.matchAll())
                    .fetchTotalHitCount();
        }
    }
}
