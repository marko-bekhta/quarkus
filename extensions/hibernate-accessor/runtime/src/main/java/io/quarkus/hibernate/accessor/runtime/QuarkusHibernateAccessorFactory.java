package io.quarkus.hibernate.accessor.runtime;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

public class QuarkusHibernateAccessorFactory implements HibernateAccessorFactory {
    private static final HibernateAccessorFactory INSTANCE = new QuarkusHibernateAccessorFactory();

    private static final Map<String, ? extends HibernateAccessorValueWriter> writerCache = new ConcurrentHashMap<>();
    private static final Map<String, ? extends HibernateAccessorValueReader<?>> readerCache = new ConcurrentHashMap<>();

    public static HibernateAccessorFactory instance(MethodHandles.Lookup lookup) {
        return INSTANCE;
    }

    @Override
    public <T> HibernateAccessorInstantiator<T> instantiator(Constructor<T> constructor) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Field field) {
        return readerCache.get(accessorFqcn(field.getDeclaringClass().getName(), fieldReaderClassName(field.getName())));
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Method method) {
        return readerCache.get(accessorFqcn(method.getDeclaringClass().getName(), methodReaderClassName(method.getName())));
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Field field) {
        return writerCache.get(accessorFqcn(field.getDeclaringClass().getName(), fieldWriterClassName(field.getName())));
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Method method) {
        return writerCache.get(accessorFqcn(method.getDeclaringClass().getName(), methodWriterClassName(method.getName())));
    }
}
