package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

import io.quarkus.hibernate.accessor.runtime.spi.NamingUtil;

interface HibernateAccessorGeneratorConstants {
    String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";
    String FACTORY_IMPLEMENTATION_INTERNAL = fqcnToName(QUARKUS_HIBERNATE_ACCESSOR_FACTORY);

    String GENERATED_READER_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorValueReaderImpl";
    String GENERATED_WRITER_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorValueWriterImpl";
    String GENERATED_INSTANTIATOR_IMPL = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorInstantiatorImpl";

    String READER_INTERFACE_INTERNAL = fqcnToName(HibernateAccessorValueReader.class.getName());
    String WRITER_INTERFACE_INTERNAL = fqcnToName(HibernateAccessorValueWriter.class.getName());
    String INSTANTIATOR_INTERFACE_INTERNAL = fqcnToName(HibernateAccessorInstantiator.class.getName());
    String FACTORY_INTERFACE_INTERNAL = fqcnToName(HibernateAccessorFactory.class.getName());
    String NAMING_UTIL_INTERNAL = fqcnToName(NamingUtil.class.getName());

    String METHOD_NAME_FIELD_READER_ACCESSOR = "$$__hibernateAccessor_fieldReader";
    String METHOD_NAME_METHOD_READER_ACCESSOR = "$$__hibernateAccessor_methodReader";
    String METHOD_NAME_FIELD_WRITER_ACCESSOR = "$$__hibernateAccessor_fieldWriter";
    String METHOD_NAME_METHOD_WRITER_ACCESSOR = "$$__hibernateAccessor_methodWriter";
    String METHOD_NAME_INSTANTIATOR_ACCESSOR = "$$__hibernateAccessor_instantiator";

    String PREFIX_READ_METHOD = "$$__hibernateAccessor_read";
    String PREFIX_WRITE_METHOD = "$$__hibernateAccessor_write";
    String PREFIX_CREATE_METHOD = "$$__hibernateAccessor_create";
}
