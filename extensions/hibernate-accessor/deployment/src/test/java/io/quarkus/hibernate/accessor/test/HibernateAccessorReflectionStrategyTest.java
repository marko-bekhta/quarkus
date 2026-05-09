package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorReflectionStrategyTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(SimpleEntity.class))
            .overrideConfigKey("quarkus.hibernate-accessor.strategy", "reflection");

    @Test
    void generatedFactoryClassDoesNotExist() {
        try {
            Thread.currentThread().getContextClassLoader()
                    .loadClass("io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory");
            assertThat(true).as("Generated factory should not exist in reflection mode").isFalse();
        } catch (ClassNotFoundException expected) {
            // bytecode generation was correctly skipped
        }
    }

    @Test
    void reflectionFactoryCanReadAndWriteFields() throws Exception {
        HibernateAccessorFactory factory = HibernateAccessorFactory.reflection();

        SimpleEntity entity = new SimpleEntity();
        HibernateAccessorValueWriter writer = factory.valueWriter(SimpleEntity.class.getDeclaredField("name"));
        writer.set(entity, "hello");

        HibernateAccessorValueReader<?> reader = factory.valueReader(SimpleEntity.class.getDeclaredField("name"));
        assertThat(reader.get(entity)).isEqualTo("hello");
    }
}
