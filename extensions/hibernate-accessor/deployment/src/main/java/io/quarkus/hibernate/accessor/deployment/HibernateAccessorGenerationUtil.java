package io.quarkus.hibernate.accessor.deployment;

final class HibernateAccessorGenerationUtil {

    private HibernateAccessorGenerationUtil() {
    }

    static String fqcnToName(String fqcn) {
        return fqcn.replace('.', '/');
    }

    static String composeNestedName(String outerName, String innerName) {
        return "%s$%s".formatted(outerName, innerName);
    }

}
