package io.quarkus.hibernate.orm.runtime.service;

import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;
import org.hibernate.property.access.spi.HibernateAccessorFactoryResolver;

public class QuarkusHibernateAccessorFactoryResolver implements HibernateAccessorFactoryResolver {
    @Override
    public HibernateAccessorFactory resolveHibernateAccessorFactoryResolver(MethodHandles.Lookup lookup) {
        return new ClassLoadingHibernateAccessorFactory();
    }

    public static class ClassLoadingHibernateAccessorFactory implements HibernateAccessorFactory {

        @Override
        public <T> HibernateAccessorInstantiator<T> instantiator(Constructor<T> constructor) {
            throw new UnsupportedOperationException("Not implemented yet.");
        }

        @Override
        public HibernateAccessorValueReader<?> valueReader(Field field) {
            try {
                String outerName = field.getDeclaringClass().getName();
                String simpleName = fieldReaderClassName(field.getName());
                return (HibernateAccessorValueReader<?>) FlatClassLoaderService.INSTANCE
                        .classForName(outerName + "$" + simpleName)
                        .getDeclaredField("INSTANCE")
                        .get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HibernateAccessorValueReader<?> valueReader(Method method) {
            try {
                String outerName = method.getDeclaringClass().getName();
                String simpleName = methodReaderClassName(method.getName());
                return (HibernateAccessorValueReader<?>) FlatClassLoaderService.INSTANCE
                        .classForName(outerName + "$" + simpleName)
                        .getDeclaredField("INSTANCE")
                        .get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HibernateAccessorValueWriter valueWriter(Field field) {
            try {
                String outerName = field.getDeclaringClass().getName();
                String simpleName = fieldWriterClassName(field.getName());
                return (HibernateAccessorValueWriter) FlatClassLoaderService.INSTANCE.classForName(outerName + "$" + simpleName)
                        .getDeclaredField("INSTANCE")
                        .get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HibernateAccessorValueWriter valueWriter(Method method) {
            try {
                String outerName = method.getDeclaringClass().getName();
                String simpleName = methodWriterClassName(method.getName());
                return (HibernateAccessorValueWriter) FlatClassLoaderService.INSTANCE.classForName(outerName + "$" + simpleName)
                        .getDeclaredField("INSTANCE")
                        .get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
