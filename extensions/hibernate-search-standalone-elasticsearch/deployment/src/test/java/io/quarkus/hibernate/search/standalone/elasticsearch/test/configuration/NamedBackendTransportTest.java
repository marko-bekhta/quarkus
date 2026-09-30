package io.quarkus.hibernate.search.standalone.elasticsearch.test.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.hibernate.search.backend.elasticsearch.ElasticsearchBackend;
import org.hibernate.search.mapper.pojo.standalone.mapping.SearchMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.hibernate.search.standalone.elasticsearch.test.simple.MyEntity;
import io.quarkus.test.QuarkusExtensionTest;

public class NamedBackendTransportTest {

    @RegisterExtension
    static QuarkusExtensionTest runner = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(MyEntity.class, IndexedEntityInNamedBackend.class)
                    .addAsResource("application-named-backend.properties", "application.properties"));

    @Inject
    SearchMapping mapping;

    @Test
    void separateBackendClients() {
        VertxElasticsearchClient defaultClient = mapping.backend()
                .unwrap(ElasticsearchBackend.class).client(VertxElasticsearchClient.class);
        VertxElasticsearchClient namedClient = mapping.backend("mybackend")
                .unwrap(ElasticsearchBackend.class).client(VertxElasticsearchClient.class);
        assertThat(namedClient).isNotSameAs(defaultClient);
    }
}
