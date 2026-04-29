package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.FieldMetadata;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;

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
    public ClassVisitor apply(String hostClassName, ClassVisitor classVisitor) {
        return new FieldAccessorsClassVisitor(classVisitor, field.name(), field.declaringClass(), hostClassName,
                field.readOnly());
    }

    private static class FieldAccessorsClassVisitor extends ClassVisitor {
        private final String field;
        private final String hostClassName;
        private final String declaringClass;
        private final boolean readOnly;

        protected FieldAccessorsClassVisitor(ClassVisitor visitor, String field, String declaringClass, String hostClassName,
                boolean readOnly) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.field = field;
            this.declaringClass = declaringClass;
            this.hostClassName = hostClassName;
            this.readOnly = readOnly;
        }

        @Override
        public void visitEnd() {
            String outerName = fqcnToName(hostClassName);
            String simpleName = fieldReaderClassName(declaringClass, hostClassName, field);
            String internalName = accessorFqcn(outerName, simpleName);
            cv.visitInnerClass(
                    internalName,
                    outerName,
                    simpleName,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            cv.visitNestMember(internalName);

            if (!readOnly) {
                simpleName = fieldWriterClassName(declaringClass, hostClassName, field);
                internalName = accessorFqcn(outerName, simpleName);
                cv.visitInnerClass(
                        internalName,
                        outerName,
                        simpleName,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
                cv.visitNestMember(internalName);
            }

            super.visitEnd();
        }
    }

}
