package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;

import java.util.List;
import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ParameterMetadata;

class HibernateAccessorHostClassFunction implements BiFunction<String, ClassVisitor, ClassVisitor>, Opcodes {

    static final String READ_METHOD = "$$__hibernateRead";
    static final String WRITE_METHOD = "$$__hibernateWrite";
    static final String CREATE_METHOD = "$$__hibernateCreate";

    private final List<ReadMember> readers;
    private final List<WriteMember> writers;
    private final List<ConstructorMetadata> constructors;

    HibernateAccessorHostClassFunction(List<ReadMember> readers, List<WriteMember> writers,
            List<ConstructorMetadata> constructors) {
        this.readers = readers;
        this.writers = writers;
        this.constructors = constructors;
    }

    @Override
    public ClassVisitor apply(String hostClassName, ClassVisitor classVisitor) {
        return new HostClassVisitor(classVisitor, readers, writers, constructors);
    }

    private static class HostClassVisitor extends ClassVisitor {
        private final List<ReadMember> readers;
        private final List<WriteMember> writers;
        private final List<ConstructorMetadata> constructors;
        private boolean isInterface;

        HostClassVisitor(ClassVisitor visitor, List<ReadMember> readers, List<WriteMember> writers,
                List<ConstructorMetadata> constructors) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.readers = readers;
            this.writers = writers;
            this.constructors = constructors;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.isInterface = (access & ACC_INTERFACE) != 0;
            if (!isInterface && (access & ACC_PUBLIC) == 0) {
                access = (access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitEnd() {
            if (!readers.isEmpty()) {
                generateReadMethod();
            }
            if (!writers.isEmpty()) {
                generateWriteMethod();
            }
            if (!constructors.isEmpty()) {
                generateCreateMethod();
            }
            super.visitEnd();
        }

        private void generateReadMethod() {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, READ_METHOD,
                    "(ILjava/lang/Object;)Ljava/lang/Object;", null, null);
            mv.visitCode();

            int count = readers.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(0, count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                ReadMember member = readers.get(i);
                String targetClass = fqcnToName(member.declaringClass());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, targetClass);

                if (member instanceof ReadField rf) {
                    mv.visitFieldInsn(GETFIELD, targetClass, rf.fieldName(), rf.descriptor());
                    if (rf.isPrimitive()) {
                        boxPrimitive(mv, rf.descriptor());
                    }
                } else if (member instanceof ReadGetter rg) {
                    String methodDescriptor = "()" + rg.descriptor();
                    int opcode = rg.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, rg.methodName(), methodDescriptor, rg.isInterface());
                    if (rg.isPrimitive()) {
                        boxPrimitive(mv, rg.descriptor());
                    }
                }

                mv.visitInsn(ARETURN);
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
            mv.visitInsn(ATHROW);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateWriteMethod() {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, WRITE_METHOD,
                    "(ILjava/lang/Object;Ljava/lang/Object;)V", null, null);
            mv.visitCode();

            int count = writers.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(0, count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                WriteMember member = writers.get(i);
                String targetClass = fqcnToName(member.declaringClass());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, targetClass);

                mv.visitVarInsn(ALOAD, 2);

                if (member instanceof WriteField wf) {
                    if (wf.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, Type.getType(wf.descriptor()));
                    } else {
                        mv.visitTypeInsn(CHECKCAST, Type.getType(wf.descriptor()).getInternalName());
                    }
                    mv.visitFieldInsn(PUTFIELD, targetClass, wf.fieldName(), wf.descriptor());
                } else if (member instanceof WriteSetter ws) {
                    if (ws.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, Type.getType(ws.descriptor()));
                    } else {
                        mv.visitTypeInsn(CHECKCAST, Type.getType(ws.descriptor()).getInternalName());
                    }
                    String methodDescriptor = "(" + ws.descriptor() + ")V";
                    int opcode = ws.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, ws.methodName(), methodDescriptor, ws.isInterface());
                }

                mv.visitInsn(RETURN);
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
            mv.visitInsn(ATHROW);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateCreateMethod() {
            int accessFlags = ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, CREATE_METHOD,
                    "(I[Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            mv.visitCode();

            int count = constructors.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(0, count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                ConstructorMetadata ctor = constructors.get(i);
                String targetClass = fqcnToName(ctor.declaringClass());

                mv.visitTypeInsn(NEW, targetClass);
                mv.visitInsn(DUP);

                for (int p = 0; p < ctor.parameters().size(); p++) {
                    ParameterMetadata param = ctor.parameters().get(p);
                    mv.visitVarInsn(ALOAD, 1);
                    pushIntConst(mv, p);
                    mv.visitInsn(AALOAD);

                    Type paramType = Type.getType(param.descriptor());
                    if (param.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, paramType);
                    } else {
                        mv.visitTypeInsn(CHECKCAST, paramType.getInternalName());
                    }
                }

                mv.visitMethodInsn(INVOKESPECIAL, targetClass, "<init>", ctor.descriptor(), false);
                mv.visitInsn(ARETURN);
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
            mv.visitInsn(ATHROW);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void boxPrimitive(MethodVisitor mv, String descriptor) {
            Type primitiveType = Type.getType(descriptor);
            Type wrapperType = AsmUtil.autobox(primitiveType);
            mv.visitMethodInsn(INVOKESTATIC, wrapperType.getInternalName(), "valueOf",
                    Type.getMethodDescriptor(wrapperType, primitiveType), false);
        }

        private static void pushIntConst(MethodVisitor mv, int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }
    }

    sealed interface ReadMember {
        String declaringClass();

        String descriptor();

        boolean isPrimitive();
    }

    record ReadField(String declaringClass, String fieldName, String descriptor, boolean isPrimitive) implements ReadMember {
    }

    record ReadGetter(String declaringClass, String methodName, String descriptor,
            boolean isPrimitive, boolean isInterface) implements ReadMember {
    }

    sealed interface WriteMember {
        String declaringClass();

        String descriptor();

        boolean isPrimitive();
    }

    record WriteField(String declaringClass, String fieldName, String descriptor, boolean isPrimitive)
            implements
                WriteMember {
    }

    record WriteSetter(String declaringClass, String methodName, String descriptor,
            boolean isPrimitive, boolean isInterface) implements WriteMember {
    }
}
