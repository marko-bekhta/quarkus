package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.STRING_SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.emitStringSwitch;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.pushIntConst;

import java.util.ArrayList;
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

    static final String FIELD_READER = "$$__hibernateFieldReader";
    static final String METHOD_READER = "$$__hibernateMethodReader";
    static final String FIELD_WRITER = "$$__hibernateFieldWriter";
    static final String METHOD_WRITER = "$$__hibernateMethodWriter";
    static final String INSTANTIATOR_ACCESSOR = "$$__hibernateInstantiator";

    // Matches org.hibernate.bytecode.enhance.spi.EnhancerConstants.PERSISTENT_FIELD_READER_PREFIX;
    // duplicated here because this module does not depend on hibernate-core.
    private static final String PERSISTENT_FIELD_READER_PREFIX = "$$_hibernate_read_";

    private static final String READER_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorValueReader";
    private static final String WRITER_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorValueWriter";
    private static final String INSTANTIATOR_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorInstantiator";

    private static final String ACCESSOR_IMPL_FACTORY_INTERNAL = "io/quarkus/hibernate/accessor/runtime/AccessorImplFactory";

    private final List<ReadMember> readers;
    private final List<WriteMember> writers;
    private final List<ConstructorMetadata> constructors;
    private final int classIndex;

    HibernateAccessorHostClassFunction(List<ReadMember> readers, List<WriteMember> writers,
            List<ConstructorMetadata> constructors, int classIndex) {
        this.readers = readers;
        this.writers = writers;
        this.constructors = constructors;
        this.classIndex = classIndex;
    }

    @Override
    public ClassVisitor apply(String hostClassName, ClassVisitor classVisitor) {
        return new HostClassVisitor(classVisitor, readers, writers, constructors, classIndex);
    }

    private static class HostClassVisitor extends ClassVisitor {
        private final List<ReadMember> readers;
        private final List<WriteMember> writers;
        private final List<ConstructorMetadata> constructors;
        private final List<ReadGetter> discoveredReaders = new ArrayList<>();
        private final int classIndex;
        private boolean isInterface;
        private String className;

        HostClassVisitor(ClassVisitor visitor, List<ReadMember> readers, List<WriteMember> writers,
                List<ConstructorMetadata> constructors, int classIndex) {
            super(AsmUtil.ASM_API_VERSION, visitor);
            this.readers = readers;
            this.writers = writers;
            this.constructors = constructors;
            this.classIndex = classIndex;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.isInterface = (access & ACC_INTERFACE) != 0;
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            if (name.startsWith(PERSISTENT_FIELD_READER_PREFIX)
                    && (access & ACC_STATIC) == 0
                    && Type.getArgumentTypes(descriptor).length == 0) {
                boolean alreadyRegistered = false;
                for (ReadMember rm : readers) {
                    if (rm instanceof ReadGetter rg && rg.methodName().equals(name)) {
                        alreadyRegistered = true;
                        break;
                    }
                }
                if (!alreadyRegistered) {
                    Type returnType = Type.getReturnType(descriptor);
                    int sort = returnType.getSort();
                    boolean isPrimitive = sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
                    discoveredReaders.add(new ReadGetter(
                            className.replace('/', '.'),
                            name,
                            descriptor,
                            isPrimitive,
                            isInterface,
                            returnType.getDescriptor()));
                }
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (!discoveredReaders.isEmpty()) {
                readers.addAll(discoveredReaders);
            }
            generateReadMethod();
            generateWriteMethod();
            generateCreateMethod();
            generateAccessorMethods();

            super.visitEnd();
        }

        private void generateAccessorMethods() {
            List<NameAndIndex> fieldReaderEntries = new ArrayList<>();
            List<NameAndIndex> methodReaderEntries = new ArrayList<>();
            for (int i = 0; i < readers.size(); i++) {
                ReadMember rm = readers.get(i);
                if (rm instanceof ReadField rf) {
                    fieldReaderEntries.add(new NameAndIndex(rf.fieldName(), i));
                } else if (rm instanceof ReadGetter rg) {
                    methodReaderEntries.add(new NameAndIndex(rg.methodName(), i));
                }
            }

            List<NameAndIndex> fieldWriterEntries = new ArrayList<>();
            List<NameAndIndex> methodWriterEntries = new ArrayList<>();
            for (int i = 0; i < writers.size(); i++) {
                WriteMember wm = writers.get(i);
                if (wm instanceof WriteField wf) {
                    fieldWriterEntries.add(new NameAndIndex(wf.fieldName(), i));
                } else if (wm instanceof WriteSetter ws) {
                    methodWriterEntries.add(new NameAndIndex(ws.methodName(), i));
                }
            }

            List<NameAndIndex> instantiatorEntries = new ArrayList<>();
            for (int i = 0; i < constructors.size(); i++) {
                instantiatorEntries.add(new NameAndIndex(constructors.get(i).descriptor(), i));
            }

            generateAccessorMethod(FIELD_READER, fieldReaderEntries,
                    classIndex, READER_INTERFACE_INTERNAL, "createReader");
            generateAccessorMethod(METHOD_READER, methodReaderEntries,
                    classIndex, READER_INTERFACE_INTERNAL, "createReader");
            generateAccessorMethod(FIELD_WRITER, fieldWriterEntries,
                    classIndex, WRITER_INTERFACE_INTERNAL, "createWriter");
            generateAccessorMethod(METHOD_WRITER, methodWriterEntries,
                    classIndex, WRITER_INTERFACE_INTERNAL, "createWriter");
            generateAccessorMethod(INSTANTIATOR_ACCESSOR, instantiatorEntries,
                    classIndex, INSTANTIATOR_INTERFACE_INTERNAL, "createInstantiator");
        }

        private void generateAccessorMethod(String methodName, List<NameAndIndex> entries,
                int classIndex, String returnTypeInternal, String factoryMethodName) {
            String returnDesc = "L" + returnTypeInternal + ";";
            String descriptor = "(Ljava/lang/String;)" + returnDesc;

            if (entries.isEmpty()) {
                generateNullReturnMethod(methodName, descriptor);
                return;
            }

            List<String> names = entries.stream().map(NameAndIndex::name).toList();

            if (names.size() <= STRING_SWITCH_CHUNK_SIZE) {
                generateAccessorSwitch(methodName, descriptor, names, entries, classIndex,
                        returnTypeInternal, factoryMethodName);
            } else {
                int numChunks = (names.size() + STRING_SWITCH_CHUNK_SIZE - 1) / STRING_SWITCH_CHUNK_SIZE;

                List<List<String>> chunkNames = new ArrayList<>();
                List<List<NameAndIndex>> chunkEntries = new ArrayList<>();
                for (int i = 0; i < numChunks; i++) {
                    chunkNames.add(new ArrayList<>());
                    chunkEntries.add(new ArrayList<>());
                }

                for (int i = 0; i < entries.size(); i++) {
                    int bucket = (names.get(i).hashCode() & 0x7FFFFFFF) % numChunks;
                    chunkNames.get(bucket).add(names.get(i));
                    chunkEntries.get(bucket).add(entries.get(i));
                }

                for (int i = 0; i < numChunks; i++) {
                    if (!chunkNames.get(i).isEmpty()) {
                        generateAccessorSwitch(methodName + "$" + i, descriptor,
                                chunkNames.get(i), chunkEntries.get(i), classIndex,
                                returnTypeInternal, factoryMethodName);
                    }
                }

                generateAccessorDispatcher(methodName, descriptor, numChunks, chunkNames);
            }
        }

        private void generateAccessorSwitch(String methodName, String descriptor,
                List<String> names, List<NameAndIndex> entries, int classIndex,
                String returnTypeInternal, String factoryMethodName) {
            MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName,
                    descriptor, null, null);
            mv.visitCode();

            Label defaultLabel = new Label();

            emitStringSwitch(mv, 0, 1, names, defaultLabel, (caseMv, caseIdx) -> {
                int memberIndex = entries.get(caseIdx).index();
                pushIntConst(caseMv, classIndex);
                pushIntConst(caseMv, memberIndex);
                caseMv.visitMethodInsn(INVOKESTATIC, ACCESSOR_IMPL_FACTORY_INTERNAL,
                        factoryMethodName, "(II)Ljava/lang/Object;", false);
                caseMv.visitTypeInsn(CHECKCAST, returnTypeInternal);
                caseMv.visitInsn(ARETURN);
            });

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateAccessorDispatcher(String methodName, String descriptor,
                int numChunks, List<List<String>> chunks) {
            MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName,
                    descriptor, null, null);
            mv.visitCode();

            Label defaultLabel = new Label();
            Label[] labels = new Label[numChunks];
            for (int i = 0; i < numChunks; i++) {
                labels[i] = chunks.get(i).isEmpty() ? defaultLabel : new Label();
            }

            mv.visitVarInsn(ALOAD, 0);
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
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, className, methodName + "$" + i,
                            descriptor, isInterface);
                    mv.visitInsn(ARETURN);
                }
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateReadMethod() {
            String descriptor = "(ILjava/lang/Object;)Ljava/lang/Object;";
            int count = readers.size();

            if (count == 0) {
                generateThrowOnlyMethod(READ_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateReadSwitch(READ_METHOD, descriptor, readers, 0);
            } else {
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateReadSwitch(READ_METHOD + "$" + chunk, descriptor,
                            readers.subList(start, end), start);
                }
                generateChunkDispatcher(READ_METHOD, descriptor, count, false);
            }
        }

        private void generateReadSwitch(String methodName, String descriptor,
                List<ReadMember> members, int indexOffset) {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, methodName, descriptor, null, null);
            mv.visitCode();

            int count = members.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(indexOffset, indexOffset + count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                ReadMember member = members.get(i);
                String targetClass = fqcnToName(member.declaringClass());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, targetClass);

                if (member instanceof ReadField rf) {
                    mv.visitFieldInsn(GETFIELD, targetClass, rf.fieldName(), rf.descriptor());
                    if (rf.isPrimitive()) {
                        boxPrimitive(mv, rf.descriptor());
                    }
                } else if (member instanceof ReadGetter rg) {
                    int opcode = rg.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, rg.methodName(), rg.descriptor(), rg.isInterface());
                    if (rg.isPrimitive()) {
                        boxPrimitive(mv, rg.returnDescriptor());
                    }
                }

                mv.visitInsn(ARETURN);
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            throwIllegalArgumentWithIndex(mv, className);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateWriteMethod() {
            String descriptor = "(ILjava/lang/Object;Ljava/lang/Object;)V";
            int count = writers.size();

            if (count == 0) {
                generateThrowOnlyMethod(WRITE_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateWriteSwitch(WRITE_METHOD, descriptor, writers, 0);
            } else {
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateWriteSwitch(WRITE_METHOD + "$" + chunk, descriptor,
                            writers.subList(start, end), start);
                }
                generateChunkDispatcher(WRITE_METHOD, descriptor, count, true);
            }
        }

        private void generateWriteSwitch(String methodName, String descriptor,
                List<WriteMember> members, int indexOffset) {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, methodName, descriptor, null, null);
            mv.visitCode();

            int count = members.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(indexOffset, indexOffset + count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                WriteMember member = members.get(i);
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
                    Type paramType = Type.getArgumentTypes(ws.descriptor())[0];
                    if (ws.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, paramType);
                    } else {
                        mv.visitTypeInsn(CHECKCAST, paramType.getInternalName());
                    }
                    int opcode = ws.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, ws.methodName(), ws.descriptor(), ws.isInterface());
                    if (!"V".equals(ws.returnDescriptor())) {
                        Type returnType = Type.getType(ws.returnDescriptor());
                        if (returnType.getSize() == 2) {
                            mv.visitInsn(POP2);
                        } else {
                            mv.visitInsn(POP);
                        }
                    }
                }

                mv.visitInsn(RETURN);
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            throwIllegalArgumentWithIndex(mv, className);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateCreateMethod() {
            String descriptor = "(I[Ljava/lang/Object;)Ljava/lang/Object;";
            int count = constructors.size();

            if (count == 0) {
                generateThrowOnlyMethod(CREATE_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateCreateSwitch(CREATE_METHOD, descriptor, constructors, 0);
            } else {
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateCreateSwitch(CREATE_METHOD + "$" + chunk, descriptor,
                            constructors.subList(start, end), start);
                }
                generateChunkDispatcher(CREATE_METHOD, descriptor, count, false);
            }
        }

        private void generateCreateSwitch(String methodName, String descriptor,
                List<ConstructorMetadata> ctors, int indexOffset) {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, methodName, descriptor, null, null);
            mv.visitCode();

            int count = ctors.size();
            Label[] labels = new Label[count];
            for (int i = 0; i < count; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            mv.visitTableSwitchInsn(indexOffset, indexOffset + count - 1, defaultLabel, labels);

            for (int i = 0; i < count; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                ConstructorMetadata ctor = ctors.get(i);
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
            throwIllegalArgumentWithIndex(mv, className);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateChunkDispatcher(String methodName, String descriptor,
                int totalCount, boolean returnsVoid) {
            int accessFlags = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = cv.visitMethod(accessFlags, methodName, descriptor, null, null);
            mv.visitCode();

            int chunkCount = (totalCount + SWITCH_CHUNK_SIZE - 1) / SWITCH_CHUNK_SIZE;
            Label[] labels = new Label[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                labels[i] = new Label();
            }
            Label defaultLabel = new Label();

            mv.visitVarInsn(ILOAD, 0);
            pushIntConst(mv, SWITCH_CHUNK_SIZE);
            mv.visitInsn(IDIV);
            mv.visitTableSwitchInsn(0, chunkCount - 1, defaultLabel, labels);

            org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(descriptor);

            for (int i = 0; i < chunkCount; i++) {
                mv.visitLabel(labels[i]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                for (int a = 0, slot = 0; a < argTypes.length; a++) {
                    mv.visitVarInsn(argTypes[a].getOpcode(ILOAD), slot);
                    slot += argTypes[a].getSize();
                }

                mv.visitMethodInsn(INVOKESTATIC, className,
                        methodName + "$" + i, descriptor, isInterface);

                if (returnsVoid) {
                    mv.visitInsn(RETURN);
                } else {
                    mv.visitInsn(ARETURN);
                }
            }

            mv.visitLabel(defaultLabel);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            throwIllegalArgumentWithIndex(mv, className);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateThrowOnlyMethod(String methodName, String descriptor) {
            MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, descriptor, null, null);
            mv.visitCode();
            throwIllegalArgumentWithIndex(mv, className);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void generateNullReturnMethod(String methodName, String descriptor) {
            MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, descriptor, null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void throwIllegalArgumentWithIndex(MethodVisitor mv, String hostClass) {
            mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
            mv.visitInsn(DUP);
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("Unknown member index ");
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                    "(Ljava/lang/String;)V", false);
            mv.visitVarInsn(ILOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(I)Ljava/lang/StringBuilder;", false);
            mv.visitLdcInsn(" for " + hostClass.replace('/', '.'));
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>",
                    "(Ljava/lang/String;)V", false);
            mv.visitInsn(ATHROW);
        }

        private static void boxPrimitive(MethodVisitor mv, String descriptor) {
            Type primitiveType = Type.getType(descriptor);
            Type wrapperType = AsmUtil.autobox(primitiveType);
            mv.visitMethodInsn(INVOKESTATIC, wrapperType.getInternalName(), "valueOf",
                    Type.getMethodDescriptor(wrapperType, primitiveType), false);
        }
    }

    record NameAndIndex(String name, int index) {
    }

    sealed interface ReadMember {
        String declaringClass();

        String descriptor();

        boolean isPrimitive();
    }

    record ReadField(String declaringClass, String fieldName, String descriptor, boolean isPrimitive) implements ReadMember {
    }

    record ReadGetter(String declaringClass, String methodName, String descriptor,
            boolean isPrimitive, boolean isInterface, String returnDescriptor) implements ReadMember {
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
            boolean isPrimitive, boolean isInterface, String returnDescriptor) implements WriteMember {
    }
}
