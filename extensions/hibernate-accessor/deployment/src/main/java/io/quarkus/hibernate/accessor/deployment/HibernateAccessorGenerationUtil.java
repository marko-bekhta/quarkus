package io.quarkus.hibernate.accessor.deployment;

/**
 * Shared constants and utilities for the accessor bytecode generators.
 */
final class HibernateAccessorGenerationUtil {

    // Maximum entries per table-switch to stay within the JVM's 64KB method bytecode limit.
    static final int SWITCH_CHUNK_SIZE = 1000;

    private HibernateAccessorGenerationUtil() {
    }

    // Converts a fully-qualified class name (dots) to ASM internal format (slashes).
    static String fqcnToName(String fqcn) {
        return fqcn.replace('.', '/');
    }

}
