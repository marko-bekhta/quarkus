package io.quarkus.hibernate.accessor.deployment;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem;

public class TypeDescriptorHelper {

    private static final Map<String, String> PRIMITIVE_TO_WRAPPER = new HashMap<>();
    private static final Map<String, String> UNBOX_METHODS = new HashMap<>();

    static {
        // Primitive descriptor to wrapper class internal name
        PRIMITIVE_TO_WRAPPER.put("I", "java/lang/Integer");
        PRIMITIVE_TO_WRAPPER.put("J", "java/lang/Long");
        PRIMITIVE_TO_WRAPPER.put("Z", "java/lang/Boolean");
        PRIMITIVE_TO_WRAPPER.put("D", "java/lang/Double");
        PRIMITIVE_TO_WRAPPER.put("F", "java/lang/Float");
        PRIMITIVE_TO_WRAPPER.put("B", "java/lang/Byte");
        PRIMITIVE_TO_WRAPPER.put("C", "java/lang/Character");
        PRIMITIVE_TO_WRAPPER.put("S", "java/lang/Short");

        // Unboxing method names
        UNBOX_METHODS.put("I", "intValue");
        UNBOX_METHODS.put("J", "longValue");
        UNBOX_METHODS.put("Z", "booleanValue");
        UNBOX_METHODS.put("D", "doubleValue");
        UNBOX_METHODS.put("F", "floatValue");
        UNBOX_METHODS.put("B", "byteValue");
        UNBOX_METHODS.put("C", "charValue");
        UNBOX_METHODS.put("S", "shortValue");
    }

    /**
     * Checks if a descriptor represents a primitive type.
     *
     * @param descriptor Type descriptor
     * @return true if primitive, false otherwise
     */
    public static boolean isPrimitive(String descriptor) {
        return descriptor != null && descriptor.length() == 1 && PRIMITIVE_TO_WRAPPER.containsKey(descriptor);
    }

    /**
     * Gets the wrapper class internal name for a primitive descriptor.
     *
     * @param primitiveDescriptor Primitive type descriptor (e.g., "I" for int)
     * @return Wrapper class internal name (e.g., "java/lang/Integer")
     */
    public static String getPrimitiveWrapper(String primitiveDescriptor) {
        String wrapper = PRIMITIVE_TO_WRAPPER.get(primitiveDescriptor);
        if (wrapper == null) {
            throw new IllegalArgumentException("Not a primitive descriptor: " + primitiveDescriptor);
        }
        return wrapper;
    }

    /**
     * Gets the unboxing method name for a primitive descriptor.
     *
     * @param primitiveDescriptor Primitive type descriptor (e.g., "I" for int)
     * @return Unboxing method name (e.g., "intValue")
     */
    public static String getUnboxMethod(String primitiveDescriptor) {
        String method = UNBOX_METHODS.get(primitiveDescriptor);
        if (method == null) {
            throw new IllegalArgumentException("Not a primitive descriptor: " + primitiveDescriptor);
        }
        return method;
    }

    /**
     * Extracts the internal type name from an object descriptor.
     *
     * @param descriptor Type descriptor (e.g., "Ljava/lang/String;")
     * @return Internal type name (e.g., "java/lang/String")
     */
    public static String getInternalTypeName(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return descriptor;
    }

    /**
     * Converts an internal class name to a fully qualified dot-separated name.
     *
     * @param internalName Internal name (e.g., "org/hibernate/test/Book")
     * @return Fully qualified name (e.g., "org.hibernate.test.Book")
     */
    public static String toQualifiedName(String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * Gets the simple class name from an internal name.
     *
     * @param internalName Internal name (e.g., "org/hibernate/test/Book")
     * @return Simple class name (e.g., "Book")
     */
    public static String getSimpleClassName(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash >= 0 ? internalName.substring(lastSlash + 1) : internalName;
    }

    /**
     * Gets the internal type name for use in bytecode.
     * For primitives, returns the wrapper class internal name.
     * For objects, extracts the class name from the descriptor.
     *
     * @return Internal type name (e.g., "java/lang/String" or "java/lang/Integer")
     */
    public static String getInternalTypeName(HibernateAccessorBuildItem.FieldMetadata field) {
        if (field.isPrimitive()) {
            return TypeDescriptorHelper.getPrimitiveWrapper(field.descriptor());
        }

        // Extract class name from descriptor
        // "Ljava/lang/String;" -> "java/lang/String"
        if (field.descriptor().startsWith("L") && field.descriptor().endsWith(";")) {
            return field.descriptor().substring(1, field.descriptor().length() - 1);
        }

        // For arrays, return the descriptor as-is
        return field.descriptor();
    }
}
