package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;

import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;

class HibernateAccessorGetterFunction implements BiFunction<String, ClassVisitor, ClassVisitor> {

    private final MethodMetadata getter;

    public HibernateAccessorGetterFunction(MethodMetadata getter) {
        this.getter = getter;
    }

    @Override
    public ClassVisitor apply(String className, ClassVisitor classVisitor) {
        return new GetterAccessorsClassVisitor(classVisitor, getter, className);
    }

    private static class GetterAccessorsClassVisitor extends ClassVisitor {
        private final MethodMetadata getter;
        private final String outerClassName;

        protected GetterAccessorsClassVisitor(ClassVisitor visitor, MethodMetadata getter, String outerClassName) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.getter = getter;
            this.outerClassName = outerClassName;
        }

        @Override
        public void visitEnd() {
            String outerName = fqcnToName(outerClassName);
            String simpleName = methodReaderClassName(getter.declaringClass(), outerClassName, getter.name());
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
