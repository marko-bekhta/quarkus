package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import org.objectweb.asm.ClassWriter;

import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

public class HibernateAccessorSetterImplementation {

    private final MethodMetadata setter;
    private final TypeMetadata outerClass;

    public HibernateAccessorSetterImplementation(MethodMetadata setter, TypeMetadata outerClass) {
        this.setter = setter;
        this.outerClass = outerClass;
    }

    public byte[] generateWriterBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        // TODO: Andreeeeeeeeeeeeeeeeeeeeea has the impl ;)

        cw.visitNestHost(fqcnToName(outerClass.name()));
        cw.visitEnd();
        return cw.toByteArray();
    }

    public String getWriterName() {
        return composeNestedName(outerClass.name(), methodWriterClassName(setter.name()));
    }
}
