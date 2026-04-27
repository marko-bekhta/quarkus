package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import org.objectweb.asm.MethodVisitor;

import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem;
import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

public class HibernateAccessorSetterImplementation extends HibernateAccessorMemberBaseImplementation {

    private final MethodMetadata setter;
    private final TypeMetadata outerClass;

    public HibernateAccessorSetterImplementation(MethodMetadata setter, TypeMetadata outerClass) {
        this.setter = setter;
        this.outerClass = outerClass;
    }

    public byte[] generateWriterBytes() {
        return generateWriter(fqcnToName(getWriterName()), outerClass, setter);
    }

    public String getWriterName() {
        return composeNestedName(outerClass.host(),
                methodWriterClassName(setter.declaringClass(), outerClass.host(), setter.name()));
    }

    @Override
    protected void doActuallyGetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        throw new UnsupportedOperationException("Cannot use setter to get values!");
    }

    @Override
    protected void doActuallySetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        String methodDescriptor = "(" + setter.descriptor() + ")V";
        mv.visitMethodInsn(INVOKEVIRTUAL, outerClassName, setter.name(), methodDescriptor, false);
    }
}
