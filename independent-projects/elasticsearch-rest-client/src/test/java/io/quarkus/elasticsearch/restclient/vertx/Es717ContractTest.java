package io.quarkus.elasticsearch.restclient.vertx;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class Es717ContractTest extends AbstractContractTest {

    @Container
    static final GenericContainer<?> ELASTICSEARCH = container(
            "docker.elastic.co/elasticsearch/elasticsearch:7.17.27",
            Map.of("xpack.security.enabled", "false", "ES_JAVA_OPTS", "-Xms512m -Xmx512m"));

    @BeforeAll
    void init() {
        setup(ELASTICSEARCH.getHost(), ELASTICSEARCH.getMappedPort(9200));
    }
}
