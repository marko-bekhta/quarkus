package io.quarkus.hibernate.accessor.test;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

abstract class HibernateAccessorBaseTest {

    protected HibernateAccessorValueReader<?> reader(Class<?> klass, String field) throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        return factory.valueReader(klass.getDeclaredField(field));
    }

    protected HibernateAccessorValueWriter writer(Class<?> klass, String field) throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        return factory.valueWriter(klass.getDeclaredField(field));
    }

    protected HibernateAccessorValueReader<?> readerMethod(Class<?> klass, String method) throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        return factory.valueReader(klass.getDeclaredMethod(method));
    }

    protected HibernateAccessorValueWriter writerMethod(Class<?> klass, String method, Class<?> parameter) throws Exception {
        HibernateAccessorFactory factory = loadGeneratedFactory();
        return factory.valueWriter(klass.getDeclaredMethod(method, parameter));
    }

    protected HibernateAccessorFactory loadGeneratedFactory() throws Exception {
        Class<?> factoryClass = Thread.currentThread().getContextClassLoader()
                .loadClass("io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory");
        return (HibernateAccessorFactory) factoryClass.getDeclaredConstructor().newInstance();
    }
}
