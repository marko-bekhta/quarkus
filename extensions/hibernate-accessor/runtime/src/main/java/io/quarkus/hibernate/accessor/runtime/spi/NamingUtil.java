package io.quarkus.hibernate.accessor.runtime.spi;

public final class NamingUtil {

    private static final String TYPE_FIELD = "Field";
    private static final String TYPE_METHOD = "Method";

    private NamingUtil() {
    }

    public static String accessorFqcn(String outerName, String innerName) {
        return "%s$%s".formatted(outerName, innerName);
    }

    public static String methodReaderClassName(String name) {
        return readerClassName(TYPE_METHOD, name);
    }

    public static String methodWriterClassName(String name) {
        return writerClassName(TYPE_METHOD, name);
    }

    public static String fieldReaderClassName(String name) {
        return readerClassName(TYPE_FIELD, name);
    }

    public static String fieldWriterClassName(String name) {
        return writerClassName(TYPE_FIELD, name);
    }

    private static String readerClassName(String type, String name) {
        return "$$_HibernateAccessorValueReader_%s_%s".formatted(type, name);
    }

    private static String writerClassName(String type, String name) {
        return "$$_HibernateAccessorValueWriter_%s_%s".formatted(type, name);
    }
}
