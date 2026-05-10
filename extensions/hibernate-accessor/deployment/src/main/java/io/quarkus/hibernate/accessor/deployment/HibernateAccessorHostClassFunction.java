package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.STRING_SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.SWITCH_CHUNK_SIZE;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.emitStringSwitch;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.nameToFqcn;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.pushIntConst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.FieldMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MemberMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ParameterMetadata;
import io.quarkus.hibernate.accessor.runtime.AccessorImplFactory;

class HibernateAccessorHostClassFunction
        implements BiFunction<String, ClassVisitor, ClassVisitor>, Opcodes, HibernateAccessorGeneratorConstants {

    static final int ACCESSOR_TRANSFORMATION_PRIORITY = -2;

    // Matches org.hibernate.bytecode.enhance.spi.EnhancerConstants.PERSISTENT_FIELD_READER_PREFIX;
    private static final String PERSISTENT_FIELD_READER_PREFIX = "$$_hibernate_read_";

    private static final String ACCESSOR_IMPL_FACTORY_INTERNAL = fqcnToName(AccessorImplFactory.class.getName());

    private final Set<MemberMetadata> readers;
    private final Set<MemberMetadata> writers;
    private final Set<ConstructorMetadata> constructors;
    private final int classIndex;

    HibernateAccessorHostClassFunction(Set<MemberMetadata> readers, Set<MemberMetadata> writers,
            Set<ConstructorMetadata> constructors, int classIndex) {
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
        private final Set<MemberMetadata> readers;
        private final Set<MemberMetadata> writers;
        private final Set<ConstructorMetadata> constructors;
        private final int classIndex;
        private boolean isInterface;
        private String className;

        HostClassVisitor(ClassVisitor visitor, Set<MemberMetadata> readers, Set<MemberMetadata> writers,
                Set<ConstructorMetadata> constructors, int classIndex) {
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
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            // Final fields won't be having a setter, so we want to remove them.
            // we do collect them at build time since some extension can provide transformations that would remove the final ...
            if ((access & ACC_FINAL) != 0) {
                writers.removeIf(member -> member.name().equals(name) && member instanceof FieldMetadata);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.startsWith(PERSISTENT_FIELD_READER_PREFIX)
                    && (access & ACC_STATIC) == 0
                    && Type.getArgumentTypes(descriptor).length == 0) {

                Type returnType = Type.getReturnType(descriptor);
                int sort = returnType.getSort();

                readers.add(new MethodMetadata(
                        name,
                        descriptor,
                        sort >= Type.BOOLEAN && sort <= Type.DOUBLE,
                        nameToFqcn(className),
                        isInterface,
                        returnType.getDescriptor()));
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            generateReadMethod();
            generateWriteMethod();
            generateCreateMethod();
            generateAccessorMethods();

            super.visitEnd();
        }

        private void generateAccessorMethods() {
            List<NameAndIndex> fieldReaderEntries = new ArrayList<>();
            List<NameAndIndex> methodReaderEntries = new ArrayList<>();
            {
                int index = 0;
                for (MemberMetadata member : readers) {
                    if (member instanceof FieldMetadata) {
                        fieldReaderEntries.add(new NameAndIndex(member.name(), index++));
                    } else {
                        methodReaderEntries.add(new NameAndIndex(member.name(), index++));
                    }
                }
            }

            List<NameAndIndex> fieldWriterEntries = new ArrayList<>();
            List<NameAndIndex> methodWriterEntries = new ArrayList<>();
            {
                int index = 0;
                for (MemberMetadata member : writers) {
                    if (member instanceof FieldMetadata) {
                        fieldWriterEntries.add(new NameAndIndex(member.name(), index++));
                    } else {
                        methodWriterEntries.add(new NameAndIndex(member.name(), index++));
                    }
                }
            }

            List<NameAndIndex> instantiatorEntries = new ArrayList<>();
            {
                int index = 0;
                for (ConstructorMetadata constructor : constructors) {
                    instantiatorEntries.add(new NameAndIndex(constructor.descriptor(), index++));
                }
            }

            generateAccessorMethod(METHOD_NAME_FIELD_READER_ACCESSOR, fieldReaderEntries,
                    classIndex, READER_INTERFACE_INTERNAL, "createReader");
            generateAccessorMethod(METHOD_NAME_METHOD_READER_ACCESSOR, methodReaderEntries,
                    classIndex, READER_INTERFACE_INTERNAL, "createReader");
            generateAccessorMethod(METHOD_NAME_FIELD_WRITER_ACCESSOR, fieldWriterEntries,
                    classIndex, WRITER_INTERFACE_INTERNAL, "createWriter");
            generateAccessorMethod(METHOD_NAME_METHOD_WRITER_ACCESSOR, methodWriterEntries,
                    classIndex, WRITER_INTERFACE_INTERNAL, "createWriter");
            generateAccessorMethod(METHOD_NAME_INSTANTIATOR_ACCESSOR, instantiatorEntries,
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
                generateThrowOnlyMethod(PREFIX_READ_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateReadSwitch(PREFIX_READ_METHOD, descriptor, readers, 0);
            } else {
                ArrayList<MemberMetadata> memberList = new ArrayList<>(readers);
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateReadSwitch(PREFIX_READ_METHOD + "$" + chunk, descriptor,
                            memberList.subList(start, end), start);
                }
                generateChunkDispatcher(PREFIX_READ_METHOD, descriptor, count, false);
            }
        }

        private void generateReadSwitch(String methodName, String descriptor,
                Collection<MemberMetadata> members, int indexOffset) {
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

            int index = 0;
            for (MemberMetadata member : members) {
                mv.visitLabel(labels[index++]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                String targetClass = fqcnToName(member.declaringClass());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, targetClass);

                if (member instanceof FieldMetadata fm) {
                    mv.visitFieldInsn(GETFIELD, targetClass, fm.name(), fm.descriptor());
                    if (fm.isPrimitive()) {
                        boxPrimitive(mv, fm.descriptor());
                    }
                } else if (member instanceof MethodMetadata mm) {
                    int opcode = mm.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, mm.name(), mm.descriptor(), mm.isInterface());
                    if (mm.isPrimitive()) {
                        boxPrimitive(mv, mm.returnDescriptor());
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
                generateThrowOnlyMethod(PREFIX_WRITE_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateWriteSwitch(PREFIX_WRITE_METHOD, descriptor, writers, 0);
            } else {
                ArrayList<MemberMetadata> memberList = new ArrayList<>(writers);
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateWriteSwitch(PREFIX_WRITE_METHOD + "$" + chunk, descriptor,
                            memberList.subList(start, end), start);
                }
                generateChunkDispatcher(PREFIX_WRITE_METHOD, descriptor, count, true);
            }
        }

        private void generateWriteSwitch(String methodName, String descriptor,
                Collection<MemberMetadata> members, int indexOffset) {
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

            int index = 0;
            for (MemberMetadata member : members) {
                mv.visitLabel(labels[index++]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

                String targetClass = fqcnToName(member.declaringClass());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, targetClass);

                mv.visitVarInsn(ALOAD, 2);

                if (member instanceof FieldMetadata fm) {
                    if (fm.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, Type.getType(fm.descriptor()));
                    } else {
                        mv.visitTypeInsn(CHECKCAST, Type.getType(fm.descriptor()).getInternalName());
                    }
                    mv.visitFieldInsn(PUTFIELD, targetClass, fm.name(), fm.descriptor());
                } else if (member instanceof MethodMetadata mm) {
                    Type paramType = Type.getArgumentTypes(mm.descriptor())[0];
                    if (mm.isPrimitive()) {
                        AsmUtil.unboxIfRequired(mv, paramType);
                    } else {
                        mv.visitTypeInsn(CHECKCAST, paramType.getInternalName());
                    }
                    int opcode = mm.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
                    mv.visitMethodInsn(opcode, targetClass, mm.name(), mm.descriptor(), mm.isInterface());
                    if (!"V".equals(mm.returnDescriptor())) {
                        Type returnType = Type.getType(mm.returnDescriptor());
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
                generateThrowOnlyMethod(PREFIX_CREATE_METHOD, descriptor);
                return;
            }

            if (count <= SWITCH_CHUNK_SIZE) {
                generateCreateSwitch(PREFIX_CREATE_METHOD, descriptor, constructors, 0);
            } else {
                ArrayList<ConstructorMetadata> memberList = new ArrayList<>(constructors);
                for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < count; chunk++) {
                    int start = chunk * SWITCH_CHUNK_SIZE;
                    int end = Math.min(start + SWITCH_CHUNK_SIZE, count);
                    generateCreateSwitch(PREFIX_CREATE_METHOD + "$" + chunk, descriptor,
                            memberList.subList(start, end), start);
                }
                generateChunkDispatcher(PREFIX_CREATE_METHOD, descriptor, count, false);
            }
        }

        private void generateCreateSwitch(String methodName, String descriptor,
                Collection<ConstructorMetadata> ctors, int indexOffset) {
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

            int index = 0;
            for (ConstructorMetadata ctor : ctors) {
                mv.visitLabel(labels[index++]);
                mv.visitFrame(F_SAME, 0, null, 0, null);

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
}
