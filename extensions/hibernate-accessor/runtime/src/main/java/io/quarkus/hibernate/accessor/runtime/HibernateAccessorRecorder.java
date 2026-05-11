package io.quarkus.hibernate.accessor.runtime;

import org.hibernate.accessor.HibernateAccessorFactory;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class HibernateAccessorRecorder {

    public void initAccessorImplFactory(String readerClass, String writerClass, String instantiatorClass) {
        AccessorImplFactory.init(readerClass, writerClass, instantiatorClass);
    }

    public RuntimeValue<HibernateAccessorFactory> createAccessorFactory(String generatedFactoryClassName) {
        try {
            Class<?> factoryClass = Class.forName(generatedFactoryClassName, true,
                    Thread.currentThread().getContextClassLoader());
            HibernateAccessorFactory factory = (HibernateAccessorFactory) factoryClass
                    .getDeclaredConstructor()
                    .newInstance();
            AccessorImplFactory.setFactory(factory);
            return new RuntimeValue<>(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate generated accessor factory: " + generatedFactoryClassName, e);
        }
    }

    public RuntimeValue<HibernateAccessorFactory> createAccessorFactoryWithFallback(String generatedFactoryClassName) {
        try {
            Class<?> factoryClass = Class.forName(generatedFactoryClassName, true,
                    Thread.currentThread().getContextClassLoader());
            HibernateAccessorFactory factory = (HibernateAccessorFactory) factoryClass
                    .getDeclaredConstructor(HibernateAccessorFactory.class)
                    .newInstance(HibernateAccessorFactory.reflection());
            AccessorImplFactory.setFactory(factory);
            return new RuntimeValue<>(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate generated accessor factory with fallback: "
                    + generatedFactoryClassName, e);
        }
    }

    public RuntimeValue<HibernateAccessorFactory> createReflectionFactory() {
        HibernateAccessorFactory factory = HibernateAccessorFactory.reflection();
        AccessorImplFactory.setFactory(factory);
        return new RuntimeValue<>(factory);
    }

}
