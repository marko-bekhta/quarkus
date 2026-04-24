package io.quarkus.hibernate.accessor.spi;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * BuildItem used to publish the types to generate direct accessors for.
 */
public final class HibernateAccessorBuildItem extends MultiBuildItem implements Comparable<HibernateAccessorBuildItem> {

    private final TypeMetadata type;
    private final Set<FieldMetadata> fields;
    private final Set<MethodMetadata> getters;
    private final Set<MethodMetadata> setters;

    public HibernateAccessorBuildItem(TypeMetadata type, Set<FieldMetadata> fields,
            Set<MethodMetadata> getters, Set<MethodMetadata> setters) {
        this.type = type;
        this.fields = fields == null ? Set.of() : fields;
        this.getters = getters == null ? Set.of() : getters;
        this.setters = setters == null ? Set.of() : setters;
    }

    public TypeMetadata getType() {
        return type;
    }

    public Set<FieldMetadata> getFields() {
        return fields;
    }

    public Set<MethodMetadata> getGetters() {
        return getters;
    }

    public Set<MethodMetadata> getSetters() {
        return setters;
    }

    @Override
    public int compareTo(HibernateAccessorBuildItem o) {
        return this.type.compareTo(o.type);
    }

    @Override
    public String toString() {
        return "HibernateAccessorBuildItem{" +
                "type=" + type +
                '}';
    }

    public static class Builder {
        private final String packageName;
        private final String type;
        private final String host;
        private Set<FieldMetadata> fields;
        private Set<MethodMetadata> getters;
        private Set<MethodMetadata> setters;

        public Builder(ClassInfo modelClass, IndexView index) {
            this.packageName = modelClass.name().packagePrefix();
            this.type = modelClass.name().toString();
            this.host = hostClass(modelClass, index);
        }

        private static String hostClass(ClassInfo modelClass, IndexView index) {
            ClassInfo curr = modelClass;
            while (!ClassInfo.NestingType.TOP_LEVEL.equals(curr.nestingType())) {
                curr = index.getClassByName(curr.enclosingClass());
            }
            return curr.name().toString();
        }

        public Builder addField(FieldInfo field) {
            if (this.fields == null) {
                this.fields = new HashSet<>();
            }
            Type fieldType = field.type();
            this.fields.add(new FieldMetadata(field.name(), fieldType.descriptor(), fieldType.kind() == Type.Kind.PRIMITIVE,
                    field.declaringClass().name().toString()));

            return this;
        }

        public Builder addGetter(MethodInfo getter) {
            if (this.getters == null) {
                this.getters = new HashSet<>();
            }
            Type returnType = getter.returnType();
            this.getters.add(new MethodMetadata(getter.name(), returnType.descriptor(),
                    returnType.kind() == Type.Kind.PRIMITIVE, getter.declaringClass().name().toString()));

            return this;
        }

        public Builder addSetter(MethodInfo setter) {
            if (this.setters == null) {
                this.setters = new HashSet<>();
            }
            Type valueType = setter.parameterType(0);
            this.setters.add(new MethodMetadata(setter.name(), valueType.descriptor(), valueType.kind() == Type.Kind.PRIMITIVE,
                    setter.declaringClass().name().toString()));

            return this;
        }

        public HibernateAccessorBuildItem build() {
            return new HibernateAccessorBuildItem(new TypeMetadata(packageName, type, host), fields, getters, setters);
        }
    }

    public record FieldMetadata(String name, String descriptor, boolean isPrimitive, String declaringClass) {
    }

    public record MethodMetadata(String name, String descriptor, boolean isPrimitive, String declaringClass) {
    }

    public record TypeMetadata(String packageName, String name, String host) implements Comparable<TypeMetadata> {
        private static final Comparator<TypeMetadata> COMPARATOR = Comparator.comparing(TypeMetadata::packageName).thenComparing(TypeMetadata::name);

        @Override
        public int compareTo(TypeMetadata o) {
            return COMPARATOR.compare(this, o);
        }
    }

}
