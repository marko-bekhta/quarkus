package io.quarkus.hibernate.accessor.deployment;

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

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        generateDispatchSwitch(mv, className, hostClasses, interfaceHosts, READ_METHOD,
                "(ILjava/lang/Object;)Ljava/lang/Object;", 1);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

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

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "set",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        generateWriteDispatchSwitch(mv, className, hostClasses, interfaceHosts);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

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

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "create",
                "([Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        generateDispatchSwitch(mv, className, hostClasses, interfaceHosts, CREATE_METHOD,
                "(I[Ljava/lang/Object;)Ljava/lang/Object;", 1);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

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
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        mv.visitInsn(ATHROW);
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
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        mv.visitInsn(ATHROW);
    }
}
