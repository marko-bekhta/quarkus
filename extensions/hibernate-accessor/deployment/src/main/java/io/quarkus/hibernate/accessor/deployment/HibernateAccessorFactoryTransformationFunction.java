package io.quarkus.hibernate.accessor.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.util.AsmUtil;

class HibernateAccessorFactoryTransformationFunction implements BiFunction<String, ClassVisitor, ClassVisitor> {

    private List<String> packages;

    public HibernateAccessorFactoryTransformationFunction() {
        this.packages = new ArrayList<>();
    }

    public void addPackage(String aPackage) {
        packages.add(aPackage);
    }

    @Override
    public ClassVisitor apply(String className, ClassVisitor classVisitor) {
        return new RewriteFactoryMethodsClassVisitor(classVisitor, packages, className);
    }

    private static class RewriteFactoryMethodsClassVisitor extends ClassVisitor {
        private final List<String> packages;

        protected RewriteFactoryMethodsClassVisitor(ClassVisitor visitor, List<String> packages, String outerClassName) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.packages = packages;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if ("populateDelegates".equals(name) && "(Ljava/util/Map;)V".equals(descriptor)) {
                return new DelegateMethodWriter(mv, packages);
            }

            return mv;
        }

        private static class DelegateMethodWriter extends MethodVisitor implements Opcodes {
            private final List<String> packages;

            public DelegateMethodWriter(MethodVisitor mv, List<String> packages) {
                super(AsmUtil.ASM_API_VERSION, mv);
                this.packages = packages;
            }

            @Override
            public void visitCode() {
                // mv.visitCode();

                for (String pkg : packages) {
                    // delegates.put("pkg.name", pkg.____HibernateAccessorFactory.INSTANCE);
                    mv.visitVarInsn(ALOAD, 0); // Load the 'delegates' Map (param 0)
                    mv.visitLdcInsn(pkg); // Load the package name string as key

                    // Get the static INSTANCE from the generated factory
                    String internalClassName = pkg.replace('.', '/') + "/____HibernateAccessorFactory";
                    mv.visitFieldInsn(GETSTATIC, internalClassName, "INSTANCE",
                            "L" + internalClassName + ";");

                    // Call Map.put(Object, Object)
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);

                    // Pop the returned value of 'put' from the stack (since we don't need it)
                    mv.visitInsn(POP);
                }

                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0); // COMPUTE_MAXS will handle this
                mv.visitEnd();
            }
        }
    }
}
