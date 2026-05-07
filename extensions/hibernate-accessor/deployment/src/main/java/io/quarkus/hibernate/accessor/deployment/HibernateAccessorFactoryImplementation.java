package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.emitStringSwitch;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.pushIntConst;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class HibernateAccessorFactoryImplementation implements Opcodes {

    static final String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";

    static final String FIELD_READER_LOOKUP = "$$__hibernateFieldReaderLookup";
    static final String METHOD_READER_LOOKUP = "$$__hibernateMethodReaderLookup";
    static final String FIELD_WRITER_LOOKUP = "$$__hibernateFieldWriterLookup";
    static final String METHOD_WRITER_LOOKUP = "$$__hibernateMethodWriterLookup";
    static final String INSTANTIATOR_LOOKUP = "$$__hibernateInstantiatorLookup";

    private static final String FACTORY_INTERNAL = fqcnToName(QUARKUS_HIBERNATE_ACCESSOR_FACTORY);

    private static final String READER_IMPL_INTERNAL = fqcnToName(
            HibernateAccessorSingleImplGenerator.READER_IMPL);
    private static final String WRITER_IMPL_INTERNAL = fqcnToName(
            HibernateAccessorSingleImplGenerator.WRITER_IMPL);
    private static final String INSTANTIATOR_IMPL_INTERNAL = fqcnToName(
            HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL);

    private static final String READER_INTERFACE = "org/hibernate/accessor/HibernateAccessorValueReader";
    private static final String WRITER_INTERFACE = "org/hibernate/accessor/HibernateAccessorValueWriter";
    private static final String INSTANTIATOR_INTERFACE = "org/hibernate/accessor/HibernateAccessorInstantiator";
    private static final String FACTORY_INTERFACE = "org/hibernate/accessor/HibernateAccessorFactory";

    private static final String NAMING_UTIL = "io/quarkus/hibernate/accessor/runtime/spi/NamingUtil";

    private static final String LOOKUP_DESCRIPTOR = "(Ljava/lang/String;)I";

    private static final int INIT_BATCH_SIZE = 500;

    private final List<ArrayEntry> fieldReaderEntries = new ArrayList<>();
    private final List<ArrayEntry> methodReaderEntries = new ArrayList<>();
    private final List<ArrayEntry> fieldWriterEntries = new ArrayList<>();
    private final List<ArrayEntry> methodWriterEntries = new ArrayList<>();
    private final List<ArrayEntry> instantiatorEntries = new ArrayList<>();

    private final Map<String, String> dispatchTargets = new LinkedHashMap<>();
    private final Set<String> interfaceTargets = new HashSet<>();

    void addReaderEntry(String declaringClass, String type, String name, int classIndex, int memberIndex) {
        if ("field".equals(type)) {
            fieldReaderEntries.add(new ArrayEntry(declaringClass, name, classIndex, memberIndex));
        } else {
            methodReaderEntries.add(new ArrayEntry(declaringClass, name, classIndex, memberIndex));
        }
    }

    void addWriterEntry(String declaringClass, String type, String name, int classIndex, int memberIndex) {
        if ("field".equals(type)) {
            fieldWriterEntries.add(new ArrayEntry(declaringClass, name, classIndex, memberIndex));
        } else {
            methodWriterEntries.add(new ArrayEntry(declaringClass, name, classIndex, memberIndex));
        }
    }

    void addInstantiatorEntry(String declaringClass, String descriptor, int classIndex, int ctorIndex) {
        instantiatorEntries.add(new ArrayEntry(declaringClass, descriptor, classIndex, ctorIndex));
    }

    void registerDispatchTarget(String declaringClassFqcn, String dispatchTargetInternal, boolean isInterface) {
        dispatchTargets.put(declaringClassFqcn, dispatchTargetInternal);
        if (isInterface) {
            interfaceTargets.add(dispatchTargetInternal);
        }
    }

    byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FACTORY_INTERNAL, null,
                "java/lang/Object", new String[] { FACTORY_INTERFACE });

        generateArrayField(cw, "FIELD_READERS", READER_IMPL_INTERNAL);
        generateArrayField(cw, "METHOD_READERS", READER_IMPL_INTERNAL);
        generateArrayField(cw, "FIELD_WRITERS", WRITER_IMPL_INTERNAL);
        generateArrayField(cw, "METHOD_WRITERS", WRITER_IMPL_INTERNAL);
        generateArrayField(cw, "INSTANTIATORS", INSTANTIATOR_IMPL_INTERNAL);

        generateClinit(cw);
        generateConstructor(cw);

        generateValueReaderField(cw);
        generateValueReaderMethod(cw);
        generateValueWriterField(cw);
        generateValueWriterMethod(cw);
        generateInstantiator(cw);

        generateLookupMethod(cw, "lookupFieldReader", fieldReaderEntries, FIELD_READER_LOOKUP);
        generateLookupMethod(cw, "lookupMethodReader", methodReaderEntries, METHOD_READER_LOOKUP);
        generateLookupMethod(cw, "lookupFieldWriter", fieldWriterEntries, FIELD_WRITER_LOOKUP);
        generateLookupMethod(cw, "lookupMethodWriter", methodWriterEntries, METHOD_WRITER_LOOKUP);
        generateLookupMethod(cw, "lookupInstantiator", instantiatorEntries, INSTANTIATOR_LOOKUP);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateArrayField(ClassWriter cw, String name, String elementType) {
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, name,
                "[L" + elementType + ";", null, null).visitEnd();
    }

    private void generateClinit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        boolean hasReaders = !fieldReaderEntries.isEmpty() || !methodReaderEntries.isEmpty();
        boolean hasWriters = !fieldWriterEntries.isEmpty() || !methodWriterEntries.isEmpty();
        boolean hasInstantiators = !instantiatorEntries.isEmpty();

        List<String> initMethods = new ArrayList<>();

        if (hasReaders) {
            emitArrayInit(mv, "FIELD_READERS", READER_IMPL_INTERNAL, fieldReaderEntries);
            emitArrayInit(mv, "METHOD_READERS", READER_IMPL_INTERNAL, methodReaderEntries);
            generateBatchedInitMethods(cw, "initFieldReaders", fieldReaderEntries, READER_IMPL_INTERNAL,
                    "FIELD_READERS", initMethods);
            generateBatchedInitMethods(cw, "initMethodReaders", methodReaderEntries, READER_IMPL_INTERNAL,
                    "METHOD_READERS", initMethods);
        }
        if (hasWriters) {
            emitArrayInit(mv, "FIELD_WRITERS", WRITER_IMPL_INTERNAL, fieldWriterEntries);
            emitArrayInit(mv, "METHOD_WRITERS", WRITER_IMPL_INTERNAL, methodWriterEntries);
            generateBatchedInitMethods(cw, "initFieldWriters", fieldWriterEntries, WRITER_IMPL_INTERNAL,
                    "FIELD_WRITERS", initMethods);
            generateBatchedInitMethods(cw, "initMethodWriters", methodWriterEntries, WRITER_IMPL_INTERNAL,
                    "METHOD_WRITERS", initMethods);
        }
        if (hasInstantiators) {
            emitArrayInit(mv, "INSTANTIATORS", INSTANTIATOR_IMPL_INTERNAL, instantiatorEntries);
            generateBatchedInitMethods(cw, "initInstantiators", instantiatorEntries, INSTANTIATOR_IMPL_INTERNAL,
                    "INSTANTIATORS", initMethods);
        }

        for (String methodName : initMethods) {
            mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, methodName, "()V", false);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitArrayInit(MethodVisitor mv, String fieldName, String elementType,
            List<ArrayEntry> entries) {
        pushIntConst(mv, entries.size());
        mv.visitTypeInsn(ANEWARRAY, elementType);
        mv.visitFieldInsn(PUTSTATIC, FACTORY_INTERNAL, fieldName, "[L" + elementType + ";");
    }

    private static void generateBatchedInitMethods(ClassWriter cw, String baseName,
            List<ArrayEntry> entries, String implType, String arrayField, List<String> methodNames) {
        if (entries.isEmpty()) {
            return;
        }
        String arrayDesc = "[L" + implType + ";";

        for (int batch = 0; batch * INIT_BATCH_SIZE < entries.size(); batch++) {
            int start = batch * INIT_BATCH_SIZE;
            int end = Math.min(start + INIT_BATCH_SIZE, entries.size());
            String methodName = baseName + "$" + batch;
            methodNames.add(methodName);

            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, methodName, "()V", null, null);
            mv.visitCode();

            mv.visitFieldInsn(GETSTATIC, FACTORY_INTERNAL, arrayField, arrayDesc);
            mv.visitVarInsn(ASTORE, 0);

            for (int i = start; i < end; i++) {
                ArrayEntry entry = entries.get(i);
                mv.visitVarInsn(ALOAD, 0);
                pushIntConst(mv, i);
                mv.visitTypeInsn(NEW, implType);
                mv.visitInsn(DUP);
                pushIntConst(mv, entry.classIndex());
                pushIntConst(mv, entry.memberIndex());
                mv.visitMethodInsn(INVOKESPECIAL, implType, "<init>", "(II)V", false);
                mv.visitInsn(AASTORE);
            }

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    private static void generateConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // valueReader(Field) -> lookupFieldReader(className, fieldName) -> FIELD_READERS[idx]
    private static void generateValueReaderField(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "valueReader",
                "(Ljava/lang/reflect/Field;)L" + READER_INTERFACE + ";", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "lookupFieldReader",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        mv.visitVarInsn(ISTORE, 4);

        emitArrayReturnOrThrow(mv, "FIELD_READERS", READER_IMPL_INTERNAL, 4);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateValueReaderMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "valueReader",
                "(Ljava/lang/reflect/Method;)L" + READER_INTERFACE + ";", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "lookupMethodReader",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        mv.visitVarInsn(ISTORE, 4);

        emitArrayReturnOrThrow(mv, "METHOD_READERS", READER_IMPL_INTERNAL, 4);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateValueWriterField(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "valueWriter",
                "(Ljava/lang/reflect/Field;)L" + WRITER_INTERFACE + ";", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "lookupFieldWriter",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        mv.visitVarInsn(ISTORE, 4);

        emitArrayReturnOrThrow(mv, "FIELD_WRITERS", WRITER_IMPL_INTERNAL, 4);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateValueWriterMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "valueWriter",
                "(Ljava/lang/reflect/Method;)L" + WRITER_INTERFACE + ";", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "lookupMethodWriter",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        mv.visitVarInsn(ISTORE, 4);

        emitArrayReturnOrThrow(mv, "METHOD_WRITERS", WRITER_IMPL_INTERNAL, 4);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateInstantiator(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "instantiator",
                "(Ljava/lang/reflect/Constructor;)L" + INSTANTIATOR_INTERFACE + ";",
                null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "getDeclaringClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, NAMING_UTIL, "constructorDescriptor",
                "(Ljava/lang/reflect/Constructor;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "lookupInstantiator",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        mv.visitVarInsn(ISTORE, 4);

        emitArrayReturnOrThrow(mv, "INSTANTIATORS", INSTANTIATOR_IMPL_INTERNAL, 4);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitArrayReturnOrThrow(MethodVisitor mv, String arrayField,
            String elementType, int idxLocal) {
        Label throwLabel = new Label();
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitJumpInsn(IFLT, throwLabel);

        mv.visitFieldInsn(GETSTATIC, FACTORY_INTERNAL, arrayField, "[L" + elementType + ";");
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitInsn(AALOAD);
        mv.visitInsn(ARETURN);

        mv.visitLabel(throwLabel);
        mv.visitFrame(F_FULL, 5,
                new Object[] { FACTORY_INTERNAL, "java/lang/Object", "java/lang/String",
                        "java/lang/String", INTEGER },
                0, null);
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

    // Generates: static int lookupXxx(String className, String memberName) { ... }
    // Outer switch on className delegates to host class lookup, adds base offset.
    // Locals: slot 0 = className, slot 1 = memberName, slot 2 = outer temp, slot 3 = localIdx
    private void generateLookupMethod(ClassWriter cw, String methodName,
            List<ArrayEntry> entries, String hostLookupMethodName) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, methodName,
                "(Ljava/lang/String;Ljava/lang/String;)I", null, null);
        mv.visitCode();

        if (entries.isEmpty()) {
            pushIntConst(mv, -1);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        // Compute unique classes and their base offsets from the entries list
        Map<String, Integer> classBaseOffsets = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            classBaseOffsets.putIfAbsent(entries.get(i).declaringClass(), i);
        }

        List<String> classNames = classBaseOffsets.keySet().stream().toList();
        Label defaultLabel = new Label();

        // Single-level string switch on className (slot 0, temp in slot 2)
        emitStringSwitch(mv, 0, 2, classNames, defaultLabel, (caseMv, classIdx) -> {
            String className = classNames.get(classIdx);
            String target = dispatchTargets.get(className);
            int baseOffset = classBaseOffsets.get(className);

            // int localIdx = DispatchTarget.hostLookupMethod(memberName);
            caseMv.visitVarInsn(ALOAD, 1);
            caseMv.visitMethodInsn(INVOKESTATIC, target, hostLookupMethodName,
                    LOOKUP_DESCRIPTOR, interfaceTargets.contains(target));
            caseMv.visitVarInsn(ISTORE, 3);

            // if (localIdx < 0) goto default
            caseMv.visitVarInsn(ILOAD, 3);
            Label notFound = new Label();
            caseMv.visitJumpInsn(IFLT, notFound);

            // return baseOffset + localIdx
            if (baseOffset == 0) {
                caseMv.visitVarInsn(ILOAD, 3);
            } else {
                pushIntConst(caseMv, baseOffset);
                caseMv.visitVarInsn(ILOAD, 3);
                caseMv.visitInsn(IADD);
            }
            caseMv.visitInsn(IRETURN);

            caseMv.visitLabel(notFound);
            caseMv.visitFrame(F_SAME, 0, null, 0, null);
            caseMv.visitJumpInsn(GOTO, defaultLabel);
        });

        // default: return -1
        mv.visitLabel(defaultLabel);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        pushIntConst(mv, -1);
        mv.visitInsn(IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    record ArrayEntry(String declaringClass, String memberName, int classIndex, int memberIndex) {
    }
}
