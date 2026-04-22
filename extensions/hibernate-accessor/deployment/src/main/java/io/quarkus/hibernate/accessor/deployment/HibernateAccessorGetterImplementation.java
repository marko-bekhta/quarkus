package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;

import org.objectweb.asm.ClassWriter;

import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

public class HibernateAccessorGetterImplementation {

    private final String getter;
    private final TypeMetadata outerClass;

    public HibernateAccessorGetterImplementation(String getter, TypeMetadata outerClass) {
        this.getter = getter;
        this.outerClass = outerClass;
    }

    public byte[] generateReaderBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        // TODO: Andreeeeeeeeeeeeeeeeeeeeea has the impl ;)

        cw.visitNestHost(fqcnToName(outerClass.name()));
        cw.visitEnd();
        return cw.toByteArray();
    }

    public String getReaderName() {
        return composeNestedName(outerClass.name(), methodReaderClassName(getter));
    }

}
