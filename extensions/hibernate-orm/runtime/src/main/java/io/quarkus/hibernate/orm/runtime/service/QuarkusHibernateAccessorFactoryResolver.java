package io.quarkus.hibernate.orm.runtime.service;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.property.access.spi.HibernateAccessorFactoryResolver;

import io.quarkus.hibernate.accessor.runtime.AccessorImplFactory;

public class QuarkusHibernateAccessorFactoryResolver implements HibernateAccessorFactoryResolver {

    private final HibernateAccessorFactory hibernateAccessorFactory;

    public QuarkusHibernateAccessorFactoryResolver() {
        this.hibernateAccessorFactory = AccessorImplFactory.getFactory();
    }

    @Override
    public HibernateAccessorFactory resolveHibernateAccessorFactory() {
        return hibernateAccessorFactory;
    }
}
