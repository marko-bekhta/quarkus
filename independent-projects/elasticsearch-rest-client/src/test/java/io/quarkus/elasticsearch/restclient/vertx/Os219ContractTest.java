package io.quarkus.elasticsearch.restclient.vertx;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class Os219ContractTest extends AbstractContractTest {

    @Container
    static final GenericContainer<?> OPENSEARCH = container(
            "opensearchproject/opensearch:2.19.0",
            Map.of("DISABLE_SECURITY_PLUGIN", "true", "OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m"));

    @BeforeAll
    void init() {
        setup(OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200));
    }
}
