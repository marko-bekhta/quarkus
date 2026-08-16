package io.quarkus.elasticsearch.restclient.vertx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class Es717ContractTest extends AbstractContractTest {

    @Container
    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            "docker.elastic.co/elasticsearch/elasticsearch:7.17.27")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(new HttpWaitStrategy().forPort(9200).forStatusCode(200))
            .withReuse(true);

    @BeforeAll
    void init() {
        setup(ELASTICSEARCH.getHost(), ELASTICSEARCH.getMappedPort(9200));
    }
}
