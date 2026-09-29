package io.quarkus.elasticsearch.vertx.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClientBuilder;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfig;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfigConfigurer;
import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

public class ElasticsearchClientConfigTest {
    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class).addClasses(TestConfigurator.class)
                            .addAsResource(new StringAsset("quarkus.elasticsearch.hosts=elasticsearch:9200"),
                                    "application.properties"))
            .overrideRuntimeConfigKey("quarkus.elasticsearch.connection-timeout", "2S")
            .overrideRuntimeConfigKey("quarkus.elasticsearch.socket-timeout", "3S")
            .overrideRuntimeConfigKey("quarkus.elasticsearch.max-connections-per-route", "7")
            .setLogRecordPredicate(record -> record.getMessage().startsWith("Deprecated configuration property"))
            .assertLogRecords(records -> assertThat(records).hasSize(3));

    @Inject
    VertxElasticsearchClient client;

    @Test
    public void customizesInjectedClient() {
        assertThat(client).isNotNull();
        assertThat(TestConfigurator.invoked).isTrue();
    }

    @ElasticsearchClientConfig
    @ApplicationScoped
    public static class TestConfigurator implements ElasticsearchClientConfigConfigurer {

        private static boolean invoked = false;

        @Override
        public void accept(VertxElasticsearchClientBuilder builder) {
            HttpClientOptions httpClientOptions = builderOption(builder, "httpClientOptions", HttpClientOptions.class);
            PoolOptions poolOptions = builderOption(builder, "poolOptions", PoolOptions.class);
            assertThat(httpClientOptions.getConnectTimeout()).isEqualTo(2000);
            assertThat(httpClientOptions.getReadIdleTimeout()).isEqualTo(3000);
            assertThat(poolOptions.getHttp1MaxSize()).isEqualTo(7);
            invoked = true;
        }

        private static <T> T builderOption(VertxElasticsearchClientBuilder builder, String name, Class<T> type) {
            try {
                Field field = VertxElasticsearchClientBuilder.class.getDeclaredField(name);
                field.setAccessible(true);
                return type.cast(field.get(builder));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

}
