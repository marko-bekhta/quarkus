package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.MethodMetadata;

class HibernateAccessorSetterFunction implements BiFunction<String, ClassVisitor, ClassVisitor> {

    private final MethodMetadata setter;

    public HibernateAccessorSetterFunction(MethodMetadata setter) {
        this.setter = setter;
    }

    @Override
    public ClassVisitor apply(String className, ClassVisitor classVisitor) {
        return new SetterAccessorsClassVisitor(classVisitor, setter, className);
    }

    private static class SetterAccessorsClassVisitor extends ClassVisitor {
        private final MethodMetadata setter;
        private final String outerClassName;

        protected SetterAccessorsClassVisitor(ClassVisitor visitor, MethodMetadata setter, String outerClassName) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.setter = setter;
            this.outerClassName = outerClassName;
        }

        @Override
        public void visitEnd() {
            String outerName = fqcnToName(outerClassName);
            String simpleName = methodWriterClassName(setter.declaringClass(), outerClassName, setter.name());
            String internalName = accessorFqcn(outerName, simpleName);
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
