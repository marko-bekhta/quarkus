package io.quarkus.hibernate.accessor.runtime.spi;

import java.lang.invoke.MethodType;

public final class NamingUtil {

    private NamingUtil() {
    }

    public static <T> String constructorDescriptor(java.lang.reflect.Constructor<T> constructor) {
        MethodType mt = MethodType.methodType(void.class, constructor.getParameterTypes());
        return mt.toMethodDescriptorString();
    }
}
