package io.quarkus.hibernate.accessor.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class HibernateAccessorPackagePrivateTest extends HibernateAccessorBaseTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(PackagePrivateEntity.class));

    @Test
    void read() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PackagePrivateEntity.class, "name");

        PackagePrivateEntity entity = new PackagePrivateEntity("a", 10);
        assertThat(reader.get(entity)).isEqualTo("a");
    }

    @Test
    void write() throws Exception {
        HibernateAccessorValueReader<?> reader = reader(PackagePrivateEntity.class, "name");
        HibernateAccessorValueWriter writer = writer(PackagePrivateEntity.class, "name");

        PackagePrivateEntity entity = new PackagePrivateEntity();
        writer.set(entity, "written-via-base");
        assertThat(reader.get(entity)).isEqualTo("written-via-base");
    }
}
