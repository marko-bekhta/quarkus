package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;

import org.objectweb.asm.MethodVisitor;

import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.TypeMetadata;

class HibernateAccessorGetterImplementation extends HibernateAccessorMemberBaseImplementation {

    private final MethodMetadata getter;
    private final TypeMetadata outerClass;

    public HibernateAccessorGetterImplementation(MethodMetadata getter, TypeMetadata outerClass) {
        this.getter = getter;
        this.outerClass = outerClass;
    }

    public byte[] generateReaderBytes() {
        return generateReader(fqcnToName(getReaderName()), outerClass, getter);
    }

    public String getReaderName() {
        return composeNestedName(outerClass.host(),
                methodReaderClassName(getter.declaringClass(), outerClass.host(), getter.name()));
    }

    @Override
    protected void doActuallyGetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        String methodDescriptor = "()" + getter.descriptor();
        mv.visitMethodInsn(INVOKEVIRTUAL, outerClassName, getter.name(), methodDescriptor, false);
    }

    @Override
    protected void doActuallySetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        throw new UnsupportedOperationException("Cannot use getter to set values!");
    }
}
