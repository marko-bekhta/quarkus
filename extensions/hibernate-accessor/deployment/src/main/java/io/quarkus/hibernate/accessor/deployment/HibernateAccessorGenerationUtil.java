package io.quarkus.hibernate.accessor.deployment;

final class HibernateAccessorGenerationUtil {

    static final int SWITCH_CHUNK_SIZE = 1000;

    private HibernateAccessorGenerationUtil() {
    }

    static String fqcnToName(String fqcn) {
        return fqcn.replace('.', '/');
    }

}
