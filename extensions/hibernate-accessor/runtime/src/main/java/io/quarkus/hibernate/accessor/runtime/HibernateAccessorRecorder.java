package io.quarkus.hibernate.accessor.runtime;

import org.hibernate.accessor.HibernateAccessorFactory;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class HibernateAccessorRecorder {

    public RuntimeValue<HibernateAccessorFactory> createAccessorFactory(String generatedFactoryClassName) {
        try {
            Class<?> factoryClass = Class.forName(generatedFactoryClassName, true,
                    Thread.currentThread().getContextClassLoader());
            HibernateAccessorFactory factory = (HibernateAccessorFactory) factoryClass
                    .getDeclaredConstructor()
                    .newInstance();
            return new RuntimeValue<>(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate generated accessor factory: " + generatedFactoryClassName, e);
        }
    }

}
