package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.CREATE_METHOD;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.READ_METHOD;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.WRITE_METHOD;

import java.util.List;
import java.util.Set;

import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class HibernateAccessorSingleImplGenerator implements Opcodes {

    static final String READER_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorValueReaderImpl";
    static final String WRITER_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorValueWriterImpl";
    static final String INSTANTIATOR_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorInstantiatorImpl";

    private static final String READER_INTERFACE = fqcnToName(HibernateAccessorValueReader.class.getName());
    private static final String WRITER_INTERFACE = fqcnToName(HibernateAccessorValueWriter.class.getName());
    private static final String INSTANTIATOR_INTERFACE = fqcnToName(HibernateAccessorInstantiator.class.getName());

    byte[] generateReaderImpl(List<String> hostClasses, Set<String> interfaceHosts) {
        String className = fqcnToName(READER_IMPL);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, className,
                "Ljava/lang/Object;L" + READER_INTERFACE + "<Ljava/lang/Object;>;",
                "java/lang/Object", new String[] { READER_INTERFACE });

        generateIndexFields(cw);
        generateIndexConstructor(cw, className);

        String methodDesc = "(Ljava/lang/Object;)Ljava/lang/Object;";
        if (hostClasses.size() <= SWITCH_CHUNK_SIZE) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "get", methodDesc, null, null);
            mv.visitCode();
            generateDispatchSwitch(mv, className, hostClasses, interfaceHosts, READ_METHOD,
                    "(ILjava/lang/Object;)Ljava/lang/Object;", 1);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } else {
            generateChunkedDispatch(cw, className, "get", methodDesc, hostClasses, interfaceHosts,
                    READ_METHOD, "(ILjava/lang/Object;)Ljava/lang/Object;", 1, false);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    byte[] generateWriterImpl(List<String> hostClasses, Set<String> interfaceHosts) {
        String className = fqcnToName(WRITER_IMPL);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, className,
                null,
                "java/lang/Object", new String[] { WRITER_INTERFACE });

        generateIndexFields(cw);
        generateIndexConstructor(cw, className);

        if (hostClasses.size() <= SWITCH_CHUNK_SIZE) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "set",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            generateWriteDispatchSwitch(mv, className, hostClasses, interfaceHosts);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } else {
            generateChunkedWriteDispatch(cw, className, hostClasses, interfaceHosts);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    byte[] generateInstantiatorImpl(List<String> hostClasses, Set<String> interfaceHosts) {
        String className = fqcnToName(INSTANTIATOR_IMPL);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, className,
                "Ljava/lang/Object;L" + INSTANTIATOR_INTERFACE + "<Ljava/lang/Object;>;",
                "java/lang/Object", new String[] { INSTANTIATOR_INTERFACE });

        generateIndexFields(cw);
        generateIndexConstructor(cw, className);

        String methodDesc = "([Ljava/lang/Object;)Ljava/lang/Object;";
        if (hostClasses.size() <= SWITCH_CHUNK_SIZE) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "create", methodDesc, null, null);
            mv.visitCode();
            generateDispatchSwitch(mv, className, hostClasses, interfaceHosts, CREATE_METHOD,
                    "(I[Ljava/lang/Object;)Ljava/lang/Object;", 1);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } else {
            generateChunkedDispatch(cw, className, "create", methodDesc, hostClasses, interfaceHosts,
                    CREATE_METHOD, "(I[Ljava/lang/Object;)Ljava/lang/Object;", 1, false);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateIndexFields(ClassWriter cw) {
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "classIndex", "I", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "memberIndex", "I", null, null).visitEnd();
    }

    private static void generateIndexConstructor(ClassWriter cw, String className) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitFieldInsn(PUTFIELD, className, "classIndex", "I");

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitFieldInsn(PUTFIELD, className, "memberIndex", "I");

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateDispatchSwitch(MethodVisitor mv, String className,
            List<String> hostClasses, Set<String> interfaceHosts,
            String staticMethodName, String staticMethodDesc, int targetArgSlot) {
        int count = hostClasses.size();
        Label[] labels = new Label[count];
        for (int i = 0; i < count; i++) {
            labels[i] = new Label();
        }
        Label defaultLabel = new Label();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "classIndex", "I");
        mv.visitTableSwitchInsn(0, count - 1, defaultLabel, labels);

        for (int i = 0; i < count; i++) {
            mv.visitLabel(labels[i]);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            String hostFqcn = hostClasses.get(i);
            String hostClass = fqcnToName(hostFqcn);
            boolean isInterface = interfaceHosts.contains(hostFqcn);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, className, "memberIndex", "I");
            mv.visitVarInsn(ALOAD, targetArgSlot);
            mv.visitMethodInsn(INVOKESTATIC, hostClass, staticMethodName, staticMethodDesc, isInterface);
            mv.visitInsn(ARETURN);
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        throwIllegalArgument(mv);
    }

    private static void generateWriteDispatchSwitch(MethodVisitor mv, String className,
            List<String> hostClasses, Set<String> interfaceHosts) {
        int count = hostClasses.size();
        Label[] labels = new Label[count];
        for (int i = 0; i < count; i++) {
            labels[i] = new Label();
        }
        Label defaultLabel = new Label();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "classIndex", "I");
        mv.visitTableSwitchInsn(0, count - 1, defaultLabel, labels);

        for (int i = 0; i < count; i++) {
            mv.visitLabel(labels[i]);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            String hostFqcn = hostClasses.get(i);
            String hostClass = fqcnToName(hostFqcn);
            boolean isInterface = interfaceHosts.contains(hostFqcn);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, className, "memberIndex", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, hostClass, WRITE_METHOD,
                    "(ILjava/lang/Object;Ljava/lang/Object;)V", isInterface);
            mv.visitInsn(RETURN);
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        throwIllegalArgument(mv);
    }

    private void generateChunkedDispatch(ClassWriter cw, String className,
            String publicMethodName, String publicMethodDesc,
            List<String> hostClasses, Set<String> interfaceHosts,
            String staticMethodName, String staticMethodDesc, int targetArgSlot,
            boolean returnsVoid) {
        int total = hostClasses.size();
        int chunkCount = (total + SWITCH_CHUNK_SIZE - 1) / SWITCH_CHUNK_SIZE;

        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int start = chunk * SWITCH_CHUNK_SIZE;
            int end = Math.min(start + SWITCH_CHUNK_SIZE, total);
            List<String> chunkHosts = hostClasses.subList(start, end);

            String chunkMethodName = publicMethodName + "$" + chunk;
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, chunkMethodName, publicMethodDesc, null, null);
            mv.visitCode();
            generateDispatchSwitchWithOffset(mv, className, chunkHosts, interfaceHosts,
                    staticMethodName, staticMethodDesc, targetArgSlot, start);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        generateImplChunkDispatcher(cw, className, publicMethodName, publicMethodDesc,
                chunkCount, returnsVoid);
    }

    private void generateChunkedWriteDispatch(ClassWriter cw, String className,
            List<String> hostClasses, Set<String> interfaceHosts) {
        int total = hostClasses.size();
        int chunkCount = (total + SWITCH_CHUNK_SIZE - 1) / SWITCH_CHUNK_SIZE;
        String publicMethodDesc = "(Ljava/lang/Object;Ljava/lang/Object;)V";

        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int start = chunk * SWITCH_CHUNK_SIZE;
            int end = Math.min(start + SWITCH_CHUNK_SIZE, total);
            List<String> chunkHosts = hostClasses.subList(start, end);

            String chunkMethodName = "set$" + chunk;
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, chunkMethodName, publicMethodDesc, null, null);
            mv.visitCode();
            generateWriteDispatchSwitchWithOffset(mv, className, chunkHosts, interfaceHosts, start);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        generateImplChunkDispatcher(cw, className, "set", publicMethodDesc, chunkCount, true);
    }

    private static void generateDispatchSwitchWithOffset(MethodVisitor mv, String className,
            List<String> hostClasses, Set<String> interfaceHosts,
            String staticMethodName, String staticMethodDesc, int targetArgSlot, int indexOffset) {
        int count = hostClasses.size();
        Label[] labels = new Label[count];
        for (int i = 0; i < count; i++) {
            labels[i] = new Label();
        }
        Label defaultLabel = new Label();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "classIndex", "I");
        mv.visitTableSwitchInsn(indexOffset, indexOffset + count - 1, defaultLabel, labels);

        for (int i = 0; i < count; i++) {
            mv.visitLabel(labels[i]);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            String hostFqcn = hostClasses.get(i);
            String hostClass = fqcnToName(hostFqcn);
            boolean isInterface = interfaceHosts.contains(hostFqcn);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, className, "memberIndex", "I");
            mv.visitVarInsn(ALOAD, targetArgSlot);
            mv.visitMethodInsn(INVOKESTATIC, hostClass, staticMethodName, staticMethodDesc, isInterface);
            mv.visitInsn(ARETURN);
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        throwIllegalArgument(mv);
    }

    private static void generateWriteDispatchSwitchWithOffset(MethodVisitor mv, String className,
            List<String> hostClasses, Set<String> interfaceHosts, int indexOffset) {
        int count = hostClasses.size();
        Label[] labels = new Label[count];
        for (int i = 0; i < count; i++) {
            labels[i] = new Label();
        }
        Label defaultLabel = new Label();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "classIndex", "I");
        mv.visitTableSwitchInsn(indexOffset, indexOffset + count - 1, defaultLabel, labels);

        for (int i = 0; i < count; i++) {
            mv.visitLabel(labels[i]);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            String hostFqcn = hostClasses.get(i);
            String hostClass = fqcnToName(hostFqcn);
            boolean isInterface = interfaceHosts.contains(hostFqcn);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, className, "memberIndex", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, hostClass, WRITE_METHOD,
                    "(ILjava/lang/Object;Ljava/lang/Object;)V", isInterface);
            mv.visitInsn(RETURN);
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        throwIllegalArgument(mv);
    }

    private static void generateImplChunkDispatcher(ClassWriter cw, String className,
            String publicMethodName, String publicMethodDesc,
            int chunkCount, boolean returnsVoid) {
        int accessFlags = ACC_PUBLIC;
        if (publicMethodDesc.startsWith("([")) {
            accessFlags |= ACC_VARARGS;
        }
        MethodVisitor mv = cw.visitMethod(accessFlags, publicMethodName, publicMethodDesc, null, null);
        mv.visitCode();

        Label[] labels = new Label[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            labels[i] = new Label();
        }
        Label defaultLabel = new Label();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "classIndex", "I");
        pushIntConst(mv, SWITCH_CHUNK_SIZE);
        mv.visitInsn(IDIV);
        mv.visitTableSwitchInsn(0, chunkCount - 1, defaultLabel, labels);

        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(publicMethodDesc);

        for (int i = 0; i < chunkCount; i++) {
            mv.visitLabel(labels[i]);
            mv.visitFrame(F_SAME, 0, null, 0, null);

            mv.visitVarInsn(ALOAD, 0);
            for (int a = 0, slot = 1; a < argTypes.length; a++) {
                mv.visitVarInsn(argTypes[a].getOpcode(ILOAD), slot);
                slot += argTypes[a].getSize();
            }

            mv.visitMethodInsn(INVOKEVIRTUAL, className,
                    publicMethodName + "$" + i, publicMethodDesc, false);

            if (returnsVoid) {
                mv.visitInsn(RETURN);
            } else {
                mv.visitInsn(ARETURN);
            }
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        throwIllegalArgument(mv);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void throwIllegalArgument(MethodVisitor mv) {
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        mv.visitInsn(ATHROW);
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
