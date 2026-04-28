package io.quarkus.hibernate.accessor.deployment;

import org.hibernate.accessor.HibernateAccessorFactory;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;

public final class HibernateAccessorFactoryBuildItem extends SimpleBuildItem {

    private final RuntimeValue<HibernateAccessorFactory> hibernateAccessorFactory;

    public HibernateAccessorFactoryBuildItem(RuntimeValue<HibernateAccessorFactory> hibernateAccessorFactory) {
        this.hibernateAccessorFactory = hibernateAccessorFactory;
    }

    public RuntimeValue<HibernateAccessorFactory> accessorFactory() {
        return hibernateAccessorFactory;
    }
}
