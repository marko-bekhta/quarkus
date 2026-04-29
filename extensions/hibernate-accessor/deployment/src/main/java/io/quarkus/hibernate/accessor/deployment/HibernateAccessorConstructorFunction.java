package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.instantiatorClassName;

import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;

class HibernateAccessorConstructorFunction implements BiFunction<String, ClassVisitor, ClassVisitor> {

    private final ConstructorMetadata constructor;

    public HibernateAccessorConstructorFunction(ConstructorMetadata constructor) {
        this.constructor = constructor;
    }

    @Override
    public ClassVisitor apply(String className, ClassVisitor classVisitor) {
        return new ConstructorAccessorsClassVisitor(classVisitor, constructor, className);
    }

    private static class ConstructorAccessorsClassVisitor extends ClassVisitor {
        private final ConstructorMetadata constructor;
        private final String outerClassName;

        protected ConstructorAccessorsClassVisitor(ClassVisitor visitor, ConstructorMetadata constructor,
                String outerClassName) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.constructor = constructor;
            this.outerClassName = outerClassName;
        }

        @Override
        public void visitEnd() {
            String outerName = fqcnToName(outerClassName);
            String simpleName = instantiatorClassName(constructor.declaringClass(), outerClassName,
                    constructor.descriptor());
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
