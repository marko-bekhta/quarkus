package io.quarkus.elasticsearch.restclient.vertx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class Os139ContractTest extends AbstractContractTest {

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            "opensearchproject/opensearch:1.3.19")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(new HttpWaitStrategy().forPort(9200).forStatusCode(200))
            .withReuse(true);

    @BeforeAll
    void init() {
        setup(OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200));
    }
}
