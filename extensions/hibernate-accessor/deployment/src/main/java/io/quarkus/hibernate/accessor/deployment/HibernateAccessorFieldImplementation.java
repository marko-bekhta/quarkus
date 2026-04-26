package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.FieldMetadata;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

import org.objectweb.asm.MethodVisitor;

import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem;

class HibernateAccessorFieldImplementation extends HibernateAccessorMemberBaseImplementation {

    private final FieldMetadata field;
    private final TypeMetadata outerClass;

    public HibernateAccessorFieldImplementation(FieldMetadata field, TypeMetadata outerClass) {
        this.field = field;
        this.outerClass = outerClass;
    }

    public byte[] generateReaderBytes() {
        return generateReader(fqcnToName(getReaderName()), outerClass, field);
    }

    public byte[] generateWriterBytes() {
        return generateWriter(fqcnToName(getWriterName()), outerClass, field);
    }

    public String getReaderName() {
        return composeNestedName(outerClass.name(), fieldReaderClassName(field.name()));
    }

    public String getWriterName() {
        return composeNestedName(outerClass.name(), fieldWriterClassName(field.name()));
    }

    @Override
    protected void doActuallyGetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        mv.visitFieldInsn(GETFIELD, outerClassName, field.name(), field.descriptor());
    }

    @Override
    protected void doActuallySetValue(MethodVisitor mv, String outerClassName,
            HibernateAccessorBuildItem.MemberMetadata member) {
        mv.visitFieldInsn(PUTFIELD, outerClassName, field.name(), field.descriptor());
    }
}
