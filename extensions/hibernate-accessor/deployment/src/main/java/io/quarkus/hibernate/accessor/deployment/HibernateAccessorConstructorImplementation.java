package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.TypeMetadata;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.instantiatorClassName;

import org.objectweb.asm.MethodVisitor;

class HibernateAccessorConstructorImplementation extends HibernateAccessorMemberBaseImplementation {

    private final ConstructorMetadata constructor;
    private final TypeMetadata outerClass;

    public HibernateAccessorConstructorImplementation(ConstructorMetadata constructor, TypeMetadata outerClass) {
        this.constructor = constructor;
        this.outerClass = outerClass;
    }

    public String getInstantiatorName() {
        return composeNestedName(outerClass.host(),
                instantiatorClassName(constructor.declaringClass(), outerClass.host(), constructor.descriptor()));
    }

    public byte[] generateInstantiator() {
        return generateInstantiator(fqcnToName(getInstantiatorName()), outerClass, constructor);
    }

    @Override
    protected void doActuallyGetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        throw new UnsupportedOperationException("Cannot use constructor to get values!");
    }

    @Override
    protected void doActuallySetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        throw new UnsupportedOperationException("Cannot use constructor to set values!");
    }
}
