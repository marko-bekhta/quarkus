package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.composeNestedName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.FieldMetadata;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.TypeMetadata;

import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.gizmo.Gizmo;
import io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory;

class HibernateAccessorFieldImplementation {

    private final FieldMetadata field;
    private final TypeMetadata outerClass;

    public HibernateAccessorFieldImplementation(FieldMetadata field, TypeMetadata outerClass) {
        this.field = field;
        this.outerClass = outerClass;
    }

    public byte[] generateReaderBytes() {
        return InnerClassGenerator.generateReader(fqcnToName(getReaderName()), outerClass, field);
    }

    public byte[] generateWriterBytes() {
        return InnerClassGenerator.generateWriter(fqcnToName(getReaderName()), outerClass, field);
    }

    public String getReaderName() {
        return composeNestedName(outerClass.name(), fieldReaderClassName(field.name()));
    }

    public String getWriterName() {
        return composeNestedName(outerClass.name(), fieldWriterClassName(field.name()));
    }

    public class InnerClassGenerator implements Opcodes {

        private static final String WRITER_INTERFACE = fqcnToName(HibernateAccessorValueWriter.class.getName());
        private static final String READER_INTERFACE = fqcnToName(HibernateAccessorValueReader.class.getName());
        private static final String FACTORY_CLASS = fqcnToName(QuarkusHibernateAccessorFactory.class.getName());

        /**
         * Generates bytecode for an accessor writer inner class.
         *
         * @param innerClassName Fully qualified internal name of inner class
         * @param outerClass Fully qualified internal name of outer class
         * @param field Field metadata
         * @return Bytecode for the inner class
         */
        public static byte[] generateWriter(String innerClassName, TypeMetadata outerClass,
                FieldMetadata field) {
            String outerClassName = fqcnToName(outerClass.name());
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

            // Visit class header
            cw.visit(
                    Gizmo.ASM_API_VERSION,
                    ACC_PUBLIC | ACC_SUPER | ACC_STATIC,
                    innerClassName,
                    null,
                    "java/lang/Object",
                    new String[] { WRITER_INTERFACE });

            // Declare nest host so the inner class can access private fields of the outer class

            cw.visitNestHost(outerClass.host());

            // Declare this as inner class of outer class
            String simpleInnerName = innerClassName.substring(outerClassName.length() + 1);
            cw.visitInnerClass(
                    innerClassName,
                    outerClassName,
                    simpleInnerName,
                    ACC_PUBLIC | ACC_STATIC);

            // Generate INSTANCE static field
            generateWriterInstanceField(cw, innerClassName);

            // Generate constructor
            generateWriterConstructor(cw, innerClassName, innerClassName, FACTORY_CLASS);

            // Generate set() method
            generateWriterSetMethod(cw, outerClassName, field);

            // Generate static initializer
            generateWriterStaticInitializer(cw, innerClassName);

            cw.visitEnd();
            return cw.toByteArray();
        }

        /**
         * Generates the public static final INSTANCE field.
         */
        private static void generateWriterInstanceField(ClassWriter cw, String innerClassName) {
            FieldVisitor fv = cw.visitField(
                    ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                    "INSTANCE",
                    "L" + innerClassName + ";",
                    null,
                    null);
            fv.visitEnd();
        }

        /**
         * Generates the constructor that registers the instance in HibernateAccessorFactory.writerCache.
         */
        private static void generateWriterConstructor(ClassWriter cw, String innerClassName, String cacheKey,
                String factoryClassName) {
            MethodVisitor mv = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "()V",
                    null,
                    null);
            mv.visitCode();

            // Call super()
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

            // HibernateAccessorFactory.writerCache.put(cacheKey, this)
            mv.visitFieldInsn(GETSTATIC, factoryClassName, "writerCache", "Ljava/util/Map;");
            mv.visitLdcInsn(cacheKey);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(
                    INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
            mv.visitInsn(POP); // Discard return value from put()

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
            mv.visitEnd();
        }

        /**
         * Generates the set(Object, Object) method that assigns the value to the field.
         */
        private static void generateWriterSetMethod(ClassWriter cw, String outerClassName, FieldMetadata field) {
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

            if (field.isPrimitive()) {
                // For primitives: cast to wrapper, then unbox
                String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(field.descriptor());
                String unboxMethod = TypeDescriptorHelper.getUnboxMethod(field.descriptor());
                String unboxDescriptor = "()" + field.descriptor();

                mv.visitTypeInsn(CHECKCAST, wrapperClass);
                mv.visitMethodInsn(INVOKEVIRTUAL, wrapperClass, unboxMethod, unboxDescriptor, false);
            } else {
                // For objects: cast to target type
                String targetType = TypeDescriptorHelper.getInternalTypeName(field);
                mv.visitTypeInsn(CHECKCAST, targetType);
            }

            // Set the field value
            // fieldName = (FieldType) var2
            mv.visitFieldInsn(PUTFIELD, outerClassName, field.name(), field.descriptor());

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
            mv.visitEnd();
        }

        /**
         * Generates the static initializer that creates and assigns the INSTANCE.
         */
        private static void generateWriterStaticInitializer(ClassWriter cw, String innerClassName) {
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

        /**
         * Generates bytecode for an accessor reader inner class.
         *
         * @param innerClassName Fully qualified internal name of inner class
         * @param outerClass Fully qualified internal name of outer class
         * @param field Field metadata
         * @return Bytecode for the inner class
         */
        public static byte[] generateReader(String innerClassName, TypeMetadata outerClass,
                FieldMetadata field) {
            String outerClassName = fqcnToName(outerClass.name());
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

            // Determine generic signature for the interface
            String fieldTypeSignature;
            if (field.isPrimitive()) {
                // For primitives, use the wrapper type in the generic signature
                String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(field.descriptor());
                fieldTypeSignature = "L" + wrapperClass + ";";
            } else {
                fieldTypeSignature = field.descriptor();
            }

            // Generic signature: implements HibernateAccessorValueReader<FieldType>
            String classSignature = "Ljava/lang/Object;L" + READER_INTERFACE + "<" + fieldTypeSignature + ">;";

            // Visit class header
            cw.visit(
                    Gizmo.ASM_API_VERSION,
                    ACC_PUBLIC | ACC_SUPER | ACC_STATIC,
                    innerClassName,
                    classSignature,
                    "java/lang/Object",
                    new String[] { READER_INTERFACE });

            // Declare nest host so the inner class can access private fields of the outer class
            cw.visitNestHost(outerClass.host());

            // Declare this as inner class of outer class
            String simpleInnerName = innerClassName.substring(outerClassName.length() + 1);
            cw.visitInnerClass(
                    innerClassName,
                    outerClassName,
                    simpleInnerName,
                    ACC_PUBLIC | ACC_STATIC);

            // Generate INSTANCE static field
            generateWriterInstanceField(cw, innerClassName);

            // Generate constructor for reader
            generateReaderConstructor(cw, innerClassName, innerClassName, FACTORY_CLASS);

            // Generate get() method
            generateGetMethod(cw, outerClassName, field);

            // Generate static initializer
            generateWriterStaticInitializer(cw, innerClassName);

            cw.visitEnd();
            return cw.toByteArray();
        }

        /**
         * Generates the constructor for reader that registers in HibernateAccessorFactory.readerCache.
         */
        private static void generateReaderConstructor(ClassWriter cw, String innerClassName, String cacheKey,
                String factoryClassName) {
            MethodVisitor mv = cw.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "()V",
                    null,
                    null);
            mv.visitCode();

            // Call super()
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

            // HibernateAccessorFactory.readerCache.put(cacheKey, this)
            mv.visitFieldInsn(GETSTATIC, factoryClassName, "readerCache", "Ljava/util/Map;");
            mv.visitLdcInsn(cacheKey);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(
                    INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
            mv.visitInsn(POP); // Discard return value from put()

            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
            mv.visitEnd();
        }

        /**
         * Generates the get(Object) method that reads the field value.
         */
        private static void generateGetMethod(ClassWriter cw, String outerClassName, FieldMetadata field) {
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

            // Get the field value
            mv.visitFieldInsn(GETFIELD, outerClassName, field.name(), field.descriptor());

            // Box primitive if necessary
            if (field.isPrimitive()) {
                String wrapperClass = TypeDescriptorHelper.getPrimitiveWrapper(field.descriptor());
                String valueOfDescriptor = "(" + field.descriptor() + ")L" + wrapperClass + ";";
                mv.visitMethodInsn(INVOKESTATIC, wrapperClass, "valueOf", valueOfDescriptor, false);
            }

            // Return the value
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0); // COMPUTE_FRAMES will calculate
            mv.visitEnd();
        }
    }
}
