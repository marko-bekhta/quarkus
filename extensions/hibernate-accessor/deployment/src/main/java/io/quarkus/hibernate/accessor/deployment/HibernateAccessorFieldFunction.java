package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.FieldMetadata;

import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.util.AsmUtil;

class HibernateAccessorFieldFunction implements BiFunction<String, ClassVisitor, ClassVisitor> {

    private final FieldMetadata field;

    public HibernateAccessorFieldFunction(FieldMetadata field) {
        this.field = field;
    }

    @Override
    public ClassVisitor apply(String className, ClassVisitor classVisitor) {
        return new FieldAccessorsClassVisitor(classVisitor, field.name(), className);
    }

    private static class FieldAccessorsClassVisitor extends ClassVisitor {
        private final String field;
        private final String outerClassName;

        protected FieldAccessorsClassVisitor(ClassVisitor visitor, String field, String outerClassName) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.field = field;
            this.outerClassName = outerClassName;
        }

        @Override
        public void visitEnd() {
            String outerName = fqcnToName(outerClassName);
            String simpleName = fieldReaderClassName(field);
            String internalName = accessorFqcn(outerName, simpleName);
            cv.visitInnerClass(
                    internalName,
                    outerName,
                    simpleName,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            cv.visitNestMember(internalName);

            simpleName = fieldWriterClassName(field);
            internalName = accessorFqcn(outerName, simpleName);
            cv.visitInnerClass(
                    internalName,
                    outerName,
                    simpleName,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            cv.visitNestMember(internalName);

            super.visitEnd();
        }
    }

}
