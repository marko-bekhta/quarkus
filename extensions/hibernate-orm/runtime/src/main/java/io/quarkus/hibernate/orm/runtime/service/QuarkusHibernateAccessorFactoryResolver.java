package io.quarkus.hibernate.orm.runtime.service;

import java.lang.invoke.MethodHandles;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.property.access.spi.HibernateAccessorFactoryResolver;

import io.quarkus.hibernate.accessor.runtime.QuarkusClassLoadingHibernateAccessorFactory;

public class QuarkusHibernateAccessorFactoryResolver implements HibernateAccessorFactoryResolver {
    @Override
    public HibernateAccessorFactory resolveHibernateAccessorFactoryResolver(MethodHandles.Lookup lookup) {
        return QuarkusClassLoadingHibernateAccessorFactory.INSTANCE;
    }
}
