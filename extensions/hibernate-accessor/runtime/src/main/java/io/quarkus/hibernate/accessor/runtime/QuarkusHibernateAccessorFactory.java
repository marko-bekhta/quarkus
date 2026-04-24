package io.quarkus.hibernate.accessor.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

public class QuarkusHibernateAccessorFactory implements HibernateAccessorFactory {

    private final Map<String, HibernateAccessorFactory> delegates;

    public QuarkusHibernateAccessorFactory(MethodHandles.Lookup lookup) {
        delegates = new HashMap<>();
        populateDelegates(delegates);
    }

    @Override
    public <T> HibernateAccessorInstantiator<T> instantiator(Constructor<T> constructor) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Field field) {
        HibernateAccessorFactory delegate = delegate(field);
        return delegate.valueReader(field);
    }

    @Override
    public HibernateAccessorValueReader<?> valueReader(Method method) {
        HibernateAccessorFactory delegate = delegate(method);
        return delegate.valueReader(method);
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Field field) {
        HibernateAccessorFactory delegate = delegate(field);
        return delegate.valueWriter(field);
    }

    @Override
    public HibernateAccessorValueWriter valueWriter(Method method) {
        HibernateAccessorFactory delegate = delegate(method);
        return delegate.valueWriter(method);
    }

    private HibernateAccessorFactory delegate(Member member) {
        String packageName = member.getDeclaringClass().getPackageName();
        HibernateAccessorFactory accessorFactory = delegates.get(packageName);
        if (accessorFactory == null) {
            throw new IllegalStateException("Unable to locate an access factory for package: " + packageName);
        }
        return accessorFactory;
    }

    private static void populateDelegates(Map<String, HibernateAccessorFactory> delegates) {
        // generated at build time
    }

}
