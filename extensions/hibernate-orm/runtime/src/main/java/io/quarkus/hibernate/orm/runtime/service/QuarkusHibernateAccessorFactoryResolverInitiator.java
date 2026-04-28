package io.quarkus.hibernate.orm.runtime.service;

import java.util.Map;

import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.property.access.spi.HibernateAccessorFactoryResolver;
import org.hibernate.service.spi.ServiceRegistryImplementor;

public class QuarkusHibernateAccessorFactoryResolverInitiator
        implements StandardServiceInitiator<HibernateAccessorFactoryResolver> {

    public static final QuarkusHibernateAccessorFactoryResolverInitiator INSTANCE = new QuarkusHibernateAccessorFactoryResolverInitiator();

    @Override
    public HibernateAccessorFactoryResolver initiateService(Map<String, Object> configurationValues,
            ServiceRegistryImplementor registry) {
        return new QuarkusHibernateAccessorFactoryResolver();
    }

    @Override
    public Class<HibernateAccessorFactoryResolver> getServiceInitiated() {
        return HibernateAccessorFactoryResolver.class;
    }
}
