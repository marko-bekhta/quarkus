package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.MemberMetadata;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

abstract class HibernateAccessorMemberBaseImplementation implements Opcodes {

    private static final String WRITER_INTERFACE = fqcnToName(HibernateAccessorValueWriter.class.getName());
    private static final String READER_INTERFACE = fqcnToName(HibernateAccessorValueReader.class.getName());

    /**
     * Generates bytecode for an accessor writer inner class.
     *
     * @param innerClassName Fully qualified internal name of inner class
     * @param outerClass Fully qualified internal name of outer class
     * @param member Processed member (field/method) metadata
     * @return Bytecode for the inner class
     */
    protected byte[] generateWriter(String innerClassName, TypeMetadata outerClass,
            MemberMetadata member) {
        String outerClassName = fqcnToName(outerClass.name());
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        // Visit class header
        cw.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                innerClassName,
                null,
                "java/lang/Object",
                new String[] { WRITER_INTERFACE });

        // Declare this as inner class of outer class
        String simpleInnerName = innerClassName.substring(outerClassName.length() + 1);
        cw.visitInnerClass(
                innerClassName,
                outerClassName,
                simpleInnerName,
                ACC_PUBLIC | ACC_STATIC);

        // Declare nest host so the inner class can access private fields of the outer class
        cw.visitNestHost(fqcnToName(outerClass.host()));

        generateInstanceField(cw, innerClassName);
        generateConstructor(cw);

        // Generate set() method
        generateWriterSetMethod(cw, outerClassName, member);

        // Generate static initializer
        generateStaticInitializer(cw, innerClassName);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates bytecode for an accessor reader inner class.
     *
     * @param innerClassName Fully qualified internal name of inner class
     * @param outerClass Fully qualified internal name of outer class
     * @param member Processed member (field/method) metadata
     * @return Bytecode for the inner class
     */
    protected byte[] generateReader(String innerClassName, TypeMetadata outerClass,
            MemberMetadata member) {
        String outerClassName = fqcnToName(outerClass.name());
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        // Determine generic signature for the interface
        String fieldTypeSignature;
        if (member.isPrimitive()) {
            // For primitives, use the wrapper type in the generic signature
            String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(member.descriptor());
            fieldTypeSignature = "L" + wrapperClass + ";";
        } else {
            fieldTypeSignature = member.descriptor();
        }

        // Generic signature: implements HibernateAccessorValueReader<FieldType>
        String classSignature = "Ljava/lang/Object;L" + READER_INTERFACE + "<" + fieldTypeSignature + ">;";

        // Visit class header
        cw.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                innerClassName,
                classSignature,
                "java/lang/Object",
                new String[] { READER_INTERFACE });

        // Declare this as inner class of outer class
        String simpleInnerName = innerClassName.substring(outerClassName.length() + 1);
        cw.visitInnerClass(
                innerClassName,
                outerClassName,
                simpleInnerName,
                ACC_PUBLIC | ACC_STATIC);

        // Declare nest host so the inner class can access private fields of the outer class
        cw.visitNestHost(fqcnToName(outerClass.host()));

        // Generate INSTANCE static field
        generateInstanceField(cw, innerClassName);

        // Generate constructor for reader
        generateConstructor(cw);

        // Generate get() method
        generateGetMethod(cw, outerClassName, member);

        // Generate static initializer
        generateStaticInitializer(cw, innerClassName);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates the get(Object) method that reads the field value.
     */
    private void generateGetMethod(ClassWriter cw, String outerClassName, MemberMetadata member) {
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC,
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        mv.visitCode();

        // Cast parameter to owner class
        // ((OwnerClass) var1)
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, outerClassName);

        // Get the field/getter value
        doActuallyGetValue(mv, outerClassName, member);

        // Box primitive if necessary
        if (member.isPrimitive()) {
            String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(member.descriptor());
            String valueOfDescriptor = "(" + member.descriptor() + ")L" + wrapperClass + ";";
            mv.visitMethodInsn(INVOKESTATIC, wrapperClass, "valueOf", valueOfDescriptor, false);
        }

        // Return the value
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
        mv.visitEnd();
    }

    /**
     * Generates the set(Object, Object) method that assigns the value to the member.
     */
    private void generateWriterSetMethod(ClassWriter cw, String outerClassName, MemberMetadata member) {
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC,
                "set",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        mv.visitCode();

        // Cast first parameter to owner class
        // ((OwnerClass) var1)
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, outerClassName);

        // Cast and potentially unbox second parameter
        mv.visitVarInsn(ALOAD, 2);

        if (member.isPrimitive()) {
            // For primitives: cast to wrapper, then unbox
            String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(member.descriptor());
            String unboxMethod = TypeDescriptorHelper.getUnboxMethod(member.descriptor());
            String unboxDescriptor = "()" + member.descriptor();

            mv.visitTypeInsn(CHECKCAST, wrapperClass);
            mv.visitMethodInsn(INVOKEVIRTUAL, wrapperClass, unboxMethod, unboxDescriptor, false);
        } else {
            // For objects: cast to target type
            String targetType = TypeDescriptorHelper.getInternalTypeName(member);
            mv.visitTypeInsn(CHECKCAST, targetType);
        }

        // Set the value
        doActuallySetValue(mv, outerClassName, member);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
        mv.visitEnd();
    }

    protected abstract void doActuallyGetValue(MethodVisitor mv, String outerClassName, MemberMetadata member);

    protected abstract void doActuallySetValue(MethodVisitor mv, String outerClassName, MemberMetadata member);

    /**
     * Generates the constructor;
     */
    private static void generateConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
        mv.visitEnd();
    }

    /**
     * Generates the public static final INSTANCE field.
     */
    private static void generateInstanceField(ClassWriter cw, String innerClassName) {
        FieldVisitor fv = cw.visitField(
                ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                "INSTANCE",
                "L" + innerClassName + ";",
                null,
                null);
        fv.visitEnd();
    }

    /**
     * Generates the static initializer that creates and assigns the INSTANCE.
     */
    private static void generateStaticInitializer(ClassWriter cw, String innerClassName) {
        MethodVisitor mv = cw.visitMethod(
                ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);
        mv.visitCode();

        // INSTANCE = new InnerClass();
        mv.visitTypeInsn(NEW, innerClassName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, innerClassName, "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, innerClassName, "INSTANCE", "L" + innerClassName + ";");

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
        mv.visitEnd();
    }
}
