package io.quarkus.hibernate.accessor.runtime.spi;

import java.lang.invoke.MethodType;

/**
 * Utility for converting reflection {@link java.lang.reflect.Constructor} objects to JVM
 * method descriptor strings. Used at runtime by the generated factory to build lookup keys.
 */
public final class NamingUtil {

    private NamingUtil() {
    }

    // Converts a Constructor's parameter types to a JVM descriptor, e.g. "(Ljava/lang/String;I)V".
    public static <T> String constructorDescriptor(java.lang.reflect.Constructor<T> constructor) {
        MethodType mt = MethodType.methodType(void.class, constructor.getParameterTypes());
        return mt.toMethodDescriptorString();
    }
}
