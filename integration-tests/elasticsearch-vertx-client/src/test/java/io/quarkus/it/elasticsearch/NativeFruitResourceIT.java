package io.quarkus.it.elasticsearch;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
public class NativeFruitResourceIT extends FruitResourceTest {

    // Execute the same tests but in native mode.

    @Test
    public void testAllApacheHttpClassesAbsentFromPackagedApplication() {
        assertThat(get("/fruits/apache-classes").jsonPath().getList("", String.class)).isEmpty();
    }
}
