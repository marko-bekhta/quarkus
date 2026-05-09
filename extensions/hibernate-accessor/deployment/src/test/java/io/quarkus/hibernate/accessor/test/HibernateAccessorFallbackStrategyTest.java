package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorFallbackStrategyTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(SimpleEntity.class, UnregisteredEntity.class))
            .overrideConfigKey("quarkus.hibernate-accessor.strategy", "reflection-free-with-fallback");

    @Test
    void generatedFactoryHasFallbackConstructor() throws Exception {
        Class<?> factoryClass = Thread.currentThread().getContextClassLoader()
                .loadClass("io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory");
        assertThat(factoryClass.getDeclaredConstructor(HibernateAccessorFactory.class)).isNotNull();
    }

    @Test
    void registeredEntityFieldAccessWorks() throws Exception {
        HibernateAccessorFactory factory = loadFallbackFactory();

        SimpleEntity entity = new SimpleEntity();
        HibernateAccessorValueWriter writer = factory.valueWriter(SimpleEntity.class.getDeclaredField("name"));
        writer.set(entity, "test");

        HibernateAccessorValueReader<?> reader = factory.valueReader(SimpleEntity.class.getDeclaredField("name"));
        assertThat(reader.get(entity)).isEqualTo("test");
    }

    @Test
    void unregisteredEntityFallsBackToReflection() throws Exception {
        HibernateAccessorFactory factory = loadFallbackFactory();

        UnregisteredEntity entity = new UnregisteredEntity();
        HibernateAccessorValueWriter writer = factory
                .valueWriter(UnregisteredEntity.class.getDeclaredField("value"));
        writer.set(entity, "fallback-value");

        HibernateAccessorValueReader<?> reader = factory
                .valueReader(UnregisteredEntity.class.getDeclaredField("value"));
        assertThat(reader.get(entity)).isEqualTo("fallback-value");
    }

    private HibernateAccessorFactory loadFallbackFactory() throws Exception {
        Class<?> factoryClass = Thread.currentThread().getContextClassLoader()
                .loadClass("io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory");
        return (HibernateAccessorFactory) factoryClass
                .getDeclaredConstructor(HibernateAccessorFactory.class)
                .newInstance(HibernateAccessorFactory.reflection());
    }
}
