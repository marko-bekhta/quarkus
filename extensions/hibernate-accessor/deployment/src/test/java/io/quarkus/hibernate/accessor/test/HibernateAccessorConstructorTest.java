package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorConstructorTest extends HibernateAccessorBaseTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(ConstructorEntity.class, PrivateConstructorEntity.class));

    @Test
    void noArgConstructor() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Constructor<ConstructorEntity> ctor = ConstructorEntity.class.getDeclaredConstructor();
        HibernateAccessorInstantiator<ConstructorEntity> instantiator = factory.instantiator(ctor);

        ConstructorEntity entity = instantiator.create();
        assertThat(entity).isNotNull();
        assertThat(entity.getName()).isNull();
        assertThat(entity.getValue()).isEqualTo(0);
    }

    @Test
    void parameterizedConstructor() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Constructor<ConstructorEntity> ctor = ConstructorEntity.class.getDeclaredConstructor(String.class, int.class);
        HibernateAccessorInstantiator<ConstructorEntity> instantiator = factory.instantiator(ctor);

        ConstructorEntity entity = instantiator.create("test", 42);
        assertThat(entity).isNotNull();
        assertThat(entity.getName()).isEqualTo("test");
        assertThat(entity.getValue()).isEqualTo(42);
    }

    @Test
    void privateNoArgConstructor() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Constructor<PrivateConstructorEntity> ctor = PrivateConstructorEntity.class.getDeclaredConstructor();
        HibernateAccessorInstantiator<PrivateConstructorEntity> instantiator = factory.instantiator(ctor);

        PrivateConstructorEntity entity = instantiator.create();
        assertThat(entity).isNotNull();
        assertThat(entity.getName()).isNull();
    }

    @Test
    void privateParameterizedConstructor() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Constructor<PrivateConstructorEntity> ctor = PrivateConstructorEntity.class.getDeclaredConstructor(String.class);
        HibernateAccessorInstantiator<PrivateConstructorEntity> instantiator = factory.instantiator(ctor);

        PrivateConstructorEntity entity = instantiator.create("private-ctor");
        assertThat(entity).isNotNull();
        assertThat(entity.getName()).isEqualTo("private-ctor");
    }

    @Test
    void constructorAndFieldAccessorsWorkTogether() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Constructor<ConstructorEntity> ctor = ConstructorEntity.class.getDeclaredConstructor();
        HibernateAccessorInstantiator<ConstructorEntity> instantiator = factory.instantiator(ctor);

        ConstructorEntity entity = instantiator.create();
        Field nameField = ConstructorEntity.class.getDeclaredField("name");
        HibernateAccessorValueReader<?> reader = factory.valueReader(nameField);
        assertThat(reader.get(entity)).isNull();

        factory.valueWriter(nameField).set(entity, "written");
        assertThat(reader.get(entity)).isEqualTo("written");
    }
}
