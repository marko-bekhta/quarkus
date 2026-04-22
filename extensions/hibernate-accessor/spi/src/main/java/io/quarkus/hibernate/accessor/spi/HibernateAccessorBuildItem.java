package io.quarkus.hibernate.accessor.spi;

import java.util.HashSet;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * BuildItem used to publish the types to generate direct accessors for.
 */
public final class HibernateAccessorBuildItem extends MultiBuildItem implements Comparable<HibernateAccessorBuildItem> {

    private final TypeMetadata type;
    private final Set<FieldMetadata> fields;
    private final Set<String> getters;
    private final Set<String> setters;

    public HibernateAccessorBuildItem(TypeMetadata type, Set<FieldMetadata> fields,
            Set<String> getters, Set<String> setters) {
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

    public Set<String> getGetters() {
        return getters;
    }

    public Set<String> getSetters() {
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
        private final String type;
        private final String host;
        private Set<FieldMetadata> fields;
        private Set<String> getters;
        private Set<String> setters;

        public Builder(ClassInfo modelClass, IndexView index) {
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
            this.fields.add(new FieldMetadata(field.name(), fieldType.descriptor(), fieldType.kind() == Type.Kind.PRIMITIVE));

            return this;
        }

        public Builder addGetter(String getter) {
            if (this.getters == null) {
                this.getters = new HashSet<>();
            }
            this.getters.add(getter);

            return this;
        }

        public Builder addSetter(String setter) {
            if (this.setters == null) {
                this.setters = new HashSet<>();
            }
            this.setters.add(setter);

            return this;
        }

        public HibernateAccessorBuildItem build() {
            return new HibernateAccessorBuildItem(new TypeMetadata(type, host), fields, getters, setters);
        }
    }

    public record FieldMetadata(String name, String descriptor, boolean isPrimitive) {
    }

    public record TypeMetadata(String name, String host) implements Comparable<TypeMetadata> {

        @Override
        public int compareTo(TypeMetadata o) {
            return name.compareTo(o.name);
        }
    }

}
