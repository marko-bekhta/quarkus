package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.STRING_SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.emitStringSwitch;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.pushIntConst;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class HibernateAccessorFactoryImplementation implements Opcodes {

    static final String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";

    static final String FIELD_READER = HibernateAccessorHostClassFunction.FIELD_READER;
    static final String METHOD_READER = HibernateAccessorHostClassFunction.METHOD_READER;
    static final String FIELD_WRITER = HibernateAccessorHostClassFunction.FIELD_WRITER;
    static final String METHOD_WRITER = HibernateAccessorHostClassFunction.METHOD_WRITER;
    static final String INSTANTIATOR_ACCESSOR = HibernateAccessorHostClassFunction.INSTANTIATOR_ACCESSOR;

    private static final String FACTORY_INTERNAL = fqcnToName(QUARKUS_HIBERNATE_ACCESSOR_FACTORY);

    private static final String READER_INTERFACE = "org/hibernate/accessor/HibernateAccessorValueReader";
    private static final String WRITER_INTERFACE = "org/hibernate/accessor/HibernateAccessorValueWriter";
    private static final String INSTANTIATOR_INTERFACE = "org/hibernate/accessor/HibernateAccessorInstantiator";
    private static final String FACTORY_INTERFACE = "org/hibernate/accessor/HibernateAccessorFactory";

    private static final String NAMING_UTIL = "io/quarkus/hibernate/accessor/runtime/spi/NamingUtil";

    private final Map<String, String> dispatchTargets = new LinkedHashMap<>();
    private final Set<String> interfaceTargets = new HashSet<>();

    private final Set<String> fieldReaderClasses = new LinkedHashSet<>();
    private final Set<String> methodReaderClasses = new LinkedHashSet<>();
    private final Set<String> fieldWriterClasses = new LinkedHashSet<>();
    private final Set<String> methodWriterClasses = new LinkedHashSet<>();
    private final Set<String> instantiatorClasses = new LinkedHashSet<>();

    void registerDispatchTarget(String declaringClassFqcn, String dispatchTargetInternal, boolean isInterface) {
        dispatchTargets.put(declaringClassFqcn, dispatchTargetInternal);
        if (isInterface) {
            interfaceTargets.add(dispatchTargetInternal);
        }
    }

    void registerFieldReader(String declaringClassFqcn) {
        fieldReaderClasses.add(declaringClassFqcn);
    }

    void registerMethodReader(String declaringClassFqcn) {
        methodReaderClasses.add(declaringClassFqcn);
    }

    void registerFieldWriter(String declaringClassFqcn) {
        fieldWriterClasses.add(declaringClassFqcn);
    }

    void registerMethodWriter(String declaringClassFqcn) {
        methodWriterClasses.add(declaringClassFqcn);
    }

    void registerInstantiator(String declaringClassFqcn) {
        instantiatorClasses.add(declaringClassFqcn);
    }

    byte[] generate(boolean withFallback) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FACTORY_INTERNAL, null,
                "java/lang/Object", new String[] { FACTORY_INTERFACE });

        if (withFallback) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "fallback", "L" + FACTORY_INTERFACE + ";", null, null).visitEnd();
        }

        generateConstructor(cw, withFallback);

        generateValueAccessor(cw, withFallback, "valueReader", "java/lang/reflect/Field", "getName",
                FIELD_READER, READER_INTERFACE, fieldReaderClasses);
        generateValueAccessor(cw, withFallback, "valueReader", "java/lang/reflect/Method", "getName",
                METHOD_READER, READER_INTERFACE, methodReaderClasses);
        generateValueAccessor(cw, withFallback, "valueWriter", "java/lang/reflect/Field", "getName",
                FIELD_WRITER, WRITER_INTERFACE, fieldWriterClasses);
        generateValueAccessor(cw, withFallback, "valueWriter", "java/lang/reflect/Method", "getName",
                METHOD_WRITER, WRITER_INTERFACE, methodWriterClasses);
        generateInstantiatorMethod(cw, withFallback);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void generateConstructor(ClassWriter cw, boolean withFallback) {
        if (withFallback) {
            String desc = "(L" + FACTORY_INTERFACE + ";)V";
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, FACTORY_INTERNAL, "fallback", "L" + FACTORY_INTERFACE + ";");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        } else {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    private void generateValueAccessor(ClassWriter cw, boolean withFallback, String factoryMethodName,
            String reflectType, String memberNameMethod,
            String hostMethodName, String returnInterface,
            Set<String> classes) {
        String returnDesc = "L" + returnInterface + ";";
        String hostMethodDesc = "(Ljava/lang/String;)" + returnDesc;

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, factoryMethodName,
                "(L" + reflectType + ";)L" + returnInterface + ";", null, null);
        mv.visitCode();

        // slot 0 = this, slot 1 = field/method arg
        // Extract className -> slot 2
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, reflectType, "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        // Extract memberName -> slot 3
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, reflectType, memberNameMethod,
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        if (classes.isEmpty()) {
            emitThrowOrFallback(mv, withFallback, factoryMethodName, reflectType, returnInterface);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        List<String> classNames = new ArrayList<>(classes);
        Label throwLabel = new Label();

        if (classNames.size() <= STRING_SWITCH_CHUNK_SIZE) {
            generateValueAccessorSwitch(mv, classNames, hostMethodName, hostMethodDesc,
                    returnInterface, throwLabel);
        } else {
            generateValueAccessorChunked(cw, mv, factoryMethodName + "_" + hostMethodName,
                    classNames, hostMethodName, hostMethodDesc, returnInterface, throwLabel);
        }

        // After switch — no class match or null result
        mv.visitLabel(throwLabel);
        mv.visitFrame(F_FULL, 4,
                new Object[] { FACTORY_INTERNAL, reflectType, "java/lang/String",
                        "java/lang/String" },
                0, null);
        emitThrowOrFallback(mv, withFallback, factoryMethodName, reflectType, returnInterface);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateValueAccessorSwitch(MethodVisitor mv, List<String> classNames,
            String hostMethodName, String hostMethodDesc,
            String implInternal, Label throwLabel) {
        Label defaultLabel = new Label();

        emitStringSwitch(mv, 2, 4, classNames, defaultLabel, (caseMv, classIdx) -> {
            String className = classNames.get(classIdx);
            String target = dispatchTargets.get(className);
            boolean isIface = interfaceTargets.contains(target);

            caseMv.visitVarInsn(ALOAD, 3);
            caseMv.visitMethodInsn(INVOKESTATIC, target, hostMethodName,
                    hostMethodDesc, isIface);
            caseMv.visitInsn(DUP);
            Label notNull = new Label();
            caseMv.visitJumpInsn(IFNONNULL, notNull);
            caseMv.visitInsn(POP);
            caseMv.visitJumpInsn(GOTO, throwLabel);
            caseMv.visitLabel(notNull);
            caseMv.visitInsn(ARETURN);
        });

        // Default of string switch — no class match
        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitJumpInsn(GOTO, throwLabel);
    }

    private void generateValueAccessorChunked(ClassWriter cw, MethodVisitor mv,
            String chunkBaseName,
            List<String> classNames, String hostMethodName, String hostMethodDesc,
            String implInternal, Label throwLabel) {
        int numChunks = (classNames.size() + STRING_SWITCH_CHUNK_SIZE - 1) / STRING_SWITCH_CHUNK_SIZE;

        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            chunks.add(new ArrayList<>());
        }
        for (String className : classNames) {
            int bucket = (className.hashCode() & 0x7FFFFFFF) % numChunks;
            chunks.get(bucket).add(className);
        }

        String chunkMethodDesc = "(Ljava/lang/String;Ljava/lang/String;)" + "L" + implInternal + ";";

        for (int i = 0; i < numChunks; i++) {
            if (!chunks.get(i).isEmpty()) {
                generateValueAccessorChunkMethod(cw, chunkBaseName + "$" + i,
                        chunkMethodDesc, chunks.get(i), hostMethodName, hostMethodDesc, implInternal);
            }
        }

        // Dispatcher in the main method
        Label defaultLabel = new Label();
        Label[] labels = new Label[numChunks];
        for (int i = 0; i < numChunks; i++) {
            labels[i] = chunks.get(i).isEmpty() ? defaultLabel : new Label();
        }

        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
        mv.visitLdcInsn(0x7FFFFFFF);
        mv.visitInsn(IAND);
        pushIntConst(mv, numChunks);
        mv.visitInsn(IREM);
        mv.visitTableSwitchInsn(0, numChunks - 1, defaultLabel, labels);

        for (int i = 0; i < numChunks; i++) {
            if (!chunks.get(i).isEmpty()) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, chunkBaseName + "$" + i,
                        chunkMethodDesc, false);
                // Check for null result
                mv.visitInsn(DUP);
                Label notNull = new Label();
                mv.visitJumpInsn(IFNONNULL, notNull);
                mv.visitInsn(POP);
                mv.visitJumpInsn(GOTO, throwLabel);
                mv.visitLabel(notNull);
                mv.visitInsn(ARETURN);
            }
        }

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitJumpInsn(GOTO, throwLabel);
    }

    private void generateValueAccessorChunkMethod(ClassWriter cw, String methodName,
            String methodDesc, List<String> classNames,
            String hostMethodName, String hostMethodDesc, String implInternal) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, methodName,
                methodDesc, null, null);
        mv.visitCode();

        Label defaultLabel = new Label();

        // slot 0 = className, slot 1 = memberName
        emitStringSwitch(mv, 0, 2, classNames, defaultLabel, (caseMv, classIdx) -> {
            String className = classNames.get(classIdx);
            String target = dispatchTargets.get(className);
            boolean isIface = interfaceTargets.contains(target);

            caseMv.visitVarInsn(ALOAD, 1);
            caseMv.visitMethodInsn(INVOKESTATIC, target, hostMethodName,
                    hostMethodDesc, isIface);
            caseMv.visitInsn(ARETURN);
        });

        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateInstantiatorMethod(ClassWriter cw, boolean withFallback) {
        String hostMethodDesc = "(Ljava/lang/String;)L" + INSTANTIATOR_INTERFACE + ";";

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "instantiator",
                "(Ljava/lang/reflect/Constructor;)L" + INSTANTIATOR_INTERFACE + ";",
                null, null);
        mv.visitCode();

        // Extract className -> slot 2
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        // Extract constructor descriptor -> slot 3
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, NAMING_UTIL, "constructorDescriptor",
                "(Ljava/lang/reflect/Constructor;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        if (instantiatorClasses.isEmpty()) {
            emitThrowOrFallback(mv, withFallback, "instantiator", "java/lang/reflect/Constructor", INSTANTIATOR_INTERFACE);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        List<String> classNames = new ArrayList<>(instantiatorClasses);
        Label throwLabel = new Label();

        if (classNames.size() <= STRING_SWITCH_CHUNK_SIZE) {
            Label defaultLabel = new Label();

            emitStringSwitch(mv, 2, 4, classNames, defaultLabel, (caseMv, classIdx) -> {
                String className = classNames.get(classIdx);
                String target = dispatchTargets.get(className);
                boolean isIface = interfaceTargets.contains(target);

                caseMv.visitVarInsn(ALOAD, 3);
                caseMv.visitMethodInsn(INVOKESTATIC, target, INSTANTIATOR_ACCESSOR,
                        hostMethodDesc, isIface);
                caseMv.visitInsn(DUP);
                Label notNull = new Label();
                caseMv.visitJumpInsn(IFNONNULL, notNull);
                caseMv.visitInsn(POP);
                caseMv.visitJumpInsn(GOTO, throwLabel);
                caseMv.visitLabel(notNull);
                caseMv.visitInsn(ARETURN);
            });

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitJumpInsn(GOTO, throwLabel);
        } else {
            // Chunked dispatch for instantiator (same pattern as value accessor)
            generateValueAccessorChunked(cw, mv, "instantiator_" + INSTANTIATOR_ACCESSOR,
                    classNames, INSTANTIATOR_ACCESSOR, hostMethodDesc,
                    INSTANTIATOR_INTERFACE, throwLabel);
        }

        mv.visitLabel(throwLabel);
        mv.visitFrame(F_FULL, 4,
                new Object[] { FACTORY_INTERNAL, "java/lang/reflect/Constructor", "java/lang/String",
                        "java/lang/String" },
                0, null);
        emitThrowOrFallback(mv, withFallback, "instantiator", "java/lang/reflect/Constructor", INSTANTIATOR_INTERFACE);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitThrowOrFallback(MethodVisitor mv, boolean withFallback, String factoryMethodName,
            String reflectType, String returnInterface) {
        if (withFallback) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, FACTORY_INTERNAL, "fallback", "L" + FACTORY_INTERFACE + ";");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, FACTORY_INTERFACE, factoryMethodName,
                    "(L" + reflectType + ";)L" + returnInterface + ";", true);
            mv.visitInsn(ARETURN);
        } else {
            emitThrow(mv);
        }
    }

    private static void emitThrow(MethodVisitor mv) {
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitLdcInsn(".");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
    }
}
