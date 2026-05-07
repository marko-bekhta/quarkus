package io.quarkus.hibernate.accessor.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field, method (getter/setter), or constructor for reflection-free accessor generation.
 * <p>
 * At build time, the Hibernate Accessor extension scans for this annotation and generates
 * optimized bytecode that replaces runtime reflection for reading, writing, and constructing
 * entity instances. This annotation is retained only at class-file level (not at runtime)
 * since it is consumed entirely during the Quarkus augmentation phase.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR })
public @interface ReflectionFreeAccessor {
}
