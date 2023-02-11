package io.quarkus.hibernate.orm.runtime.service.bytecodeprovider;

import java.util.Map;

import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.bytecode.spi.ProxyFactoryFactory;
import org.hibernate.bytecode.spi.ReflectionOptimizer;
import org.hibernate.property.access.spi.PropertyAccess;

final class RuntimeBytecodeProvider implements BytecodeProvider {

    @Override
    public ProxyFactoryFactory getProxyFactoryFactory() {
        return new NoProxyFactoryFactory();
    }

    @Override
    public ReflectionOptimizer getReflectionOptimizer(
            Class clazz,
            String[] getterNames,
            String[] setterNames,
            Class[] types) {
        return null;
    }

    @Override
    public ReflectionOptimizer getReflectionOptimizer(Class<?> clazz, Map<String, PropertyAccess> propertyAccessMap) {
        return null;
    }

    @Override
    public Enhancer getEnhancer(EnhancementContext enhancementContext) {
        return null;
    }
}
