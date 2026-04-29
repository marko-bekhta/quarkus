package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorAllPrimitivesTest extends HibernateAccessorBaseTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(PrimitiveEntity.class));

    @Test
    void booleanField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "booleanValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "booleanValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo(false);

        writer.set(entity, true);
        assertThat(reader.get(entity)).isEqualTo(true);
    }

    @Test
    void byteField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "byteValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "byteValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo((byte) 0);

        writer.set(entity, (byte) 42);
        assertThat(reader.get(entity)).isEqualTo((byte) 42);
    }

    @Test
    void charField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "charValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "charValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo('\0');

        writer.set(entity, 'A');
        assertThat(reader.get(entity)).isEqualTo('A');
    }

    @Test
    void shortField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "shortValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "shortValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo((short) 0);

        writer.set(entity, (short) 1234);
        assertThat(reader.get(entity)).isEqualTo((short) 1234);
    }

    @Test
    void intField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "intValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "intValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo(0);

        writer.set(entity, Integer.MAX_VALUE);
        assertThat(reader.get(entity)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void longField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "longValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "longValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo(0L);

        writer.set(entity, Long.MAX_VALUE);
        assertThat(reader.get(entity)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void floatField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "floatValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "floatValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo(0.0f);

        writer.set(entity, 3.14f);
        assertThat(reader.get(entity)).isEqualTo(3.14f);
    }

    @Test
    void doubleField() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PrimitiveEntity.class, "doubleValue");
        HibernateAccessorValueWriter writer = writer(PrimitiveEntity.class, "doubleValue");

        PrimitiveEntity entity = new PrimitiveEntity();
        assertThat(reader.get(entity)).isEqualTo(0.0);

        writer.set(entity, 2.718281828);
        assertThat(reader.get(entity)).isEqualTo(2.718281828);
    }

}
