package io.quarkus.hibernate.orm.runtime.service;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.property.access.spi.HibernateAccessorFactoryResolver;

public class QuarkusHibernateAccessorFactoryResolver implements HibernateAccessorFactoryResolver {

    private static final String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";

    private final HibernateAccessorFactory hibernateAccessorFactory;

    public QuarkusHibernateAccessorFactoryResolver() {
        try {
            this.hibernateAccessorFactory = (HibernateAccessorFactory) FlatClassLoaderService.INSTANCE
                    .classForName(QUARKUS_HIBERNATE_ACCESSOR_FACTORY)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to instantiate generated accessor factory: " + QUARKUS_HIBERNATE_ACCESSOR_FACTORY, e);
        }
    }

    @Override
    public HibernateAccessorFactory resolveHibernateAccessorFactoryResolver() {
        return hibernateAccessorFactory;
    }
}
