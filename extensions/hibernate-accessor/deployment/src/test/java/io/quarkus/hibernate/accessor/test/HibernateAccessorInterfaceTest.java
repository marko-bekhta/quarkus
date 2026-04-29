package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorInterfaceTest extends HibernateAccessorBaseTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(
                    AccessorInterface.class,
                    InterfaceImplEntity.class));

    @Test
    void readViaInterfaceGetter() throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        Method getter = AccessorInterface.class.getDeclaredMethod("getLabel");
        HibernateAccessorValueReader<?> reader = factory.valueReader(getter);

        InterfaceImplEntity entity = new InterfaceImplEntity("iface-value");
        assertThat(reader.get(entity)).isEqualTo("iface-value");
    }

}
