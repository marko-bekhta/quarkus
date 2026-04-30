package io.quarkus.hibernate.accessor.deployment;

final class HibernateAccessorGenerationUtil {

    private HibernateAccessorGenerationUtil() {
    }

    static String fqcnToName(String fqcn) {
        return fqcn.replace('.', '/');
    }

}
