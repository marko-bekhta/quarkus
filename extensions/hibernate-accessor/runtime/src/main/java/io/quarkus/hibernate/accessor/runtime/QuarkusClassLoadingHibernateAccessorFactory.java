package io.quarkus.hibernate.accessor.runtime;

import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

public class QuarkusClassLoadingHibernateAccessorFactory implements HibernateAccessorFactory {

    public static final QuarkusClassLoadingHibernateAccessorFactory INSTANCE = new QuarkusClassLoadingHibernateAccessorFactory();

    @Override
    public <T> HibernateAccessorInstantiator<T> instantiator(Constructor<T> constructor) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Field field) {
        try {
            String outerName = field.getDeclaringClass().getNestHost().getName();
            String simpleName = fieldReaderClassName(field.getDeclaringClass().getName(),
                    field.getDeclaringClass().getNestHost().getName(),
                    field.getName());
            return (HibernateAccessorValueReader<?>) classForName(outerName + "$" + simpleName)
                    .getDeclaredField("INSTANCE")
                    .get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Method method) {
        try {
            String outerName = method.getDeclaringClass().getNestHost().getName();
            String simpleName = methodReaderClassName(method.getDeclaringClass().getName(),
                    method.getDeclaringClass().getNestHost().getName(), method.getName());
            return (HibernateAccessorValueReader<?>) classForName(outerName + "$" + simpleName)
                    .getDeclaredField("INSTANCE")
                    .get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Field field) {
        try {
            String outerName = field.getDeclaringClass().getNestHost().getName();
            String simpleName = fieldWriterClassName(field.getDeclaringClass().getName(),
                    field.getDeclaringClass().getNestHost().getName(),
                    field.getName());
            return (HibernateAccessorValueWriter) classForName(outerName + "$" + simpleName)
                    .getDeclaredField("INSTANCE")
                    .get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Method method) {
        try {
            String outerName = method.getDeclaringClass().getNestHost().getName();
            String simpleName = methodWriterClassName(method.getDeclaringClass().getName(),
                    method.getDeclaringClass().getNestHost().getName(),
                    method.getName());
            return (HibernateAccessorValueWriter) classForName(outerName + "$" + simpleName)
                    .getDeclaredField("INSTANCE")
                    .get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> classForName(String className) {
        try {
            return (Class<T>) Class.forName(className, false, getClassLoader());
        } catch (Exception | LinkageError e) {
            throw new RuntimeException("Unable to load class [" + className + "]", e);
        }
    }

    private ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            return QuarkusClassLoadingHibernateAccessorFactory.class.getClassLoader();
        }
        return cl;
    }

}
