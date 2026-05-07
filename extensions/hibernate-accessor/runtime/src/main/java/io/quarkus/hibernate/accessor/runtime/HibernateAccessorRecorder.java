package io.quarkus.hibernate.accessor.runtime;

import org.hibernate.accessor.HibernateAccessorFactory;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Recorder invoked at static-init time to instantiate the build-time-generated
 * {@code QuarkusHibernateAccessorFactory} class via reflective constructor call.
 */
@Recorder
public class HibernateAccessorRecorder {

    /**
     * Loads the generated factory class by name and creates an instance via its no-arg constructor.
     * The class name is determined at build time by {@code HibernateAccessorFactoryImplementation}.
     */
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
