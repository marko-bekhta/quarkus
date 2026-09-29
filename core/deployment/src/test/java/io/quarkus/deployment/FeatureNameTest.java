package io.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FeatureNameTest {

    @Test
    public void testName() {
        assertEquals("agroal", Feature.AGROAL.getName());
        assertEquals("security-jpa", Feature.SECURITY_JPA.getName());
        assertEquals("elasticsearch-vertx-client", Feature.ELASTICSEARCH_VERTX_CLIENT.getName());
    }

}
