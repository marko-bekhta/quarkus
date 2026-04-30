package io.quarkus.hibernate.accessor.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
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
    private final Set<ConstructorMetadata> constructors;

    public HibernateAccessorBuildItem(TypeMetadata type, Set<FieldMetadata> fields,
            Set<MethodMetadata> getters, Set<MethodMetadata> setters, Set<ConstructorMetadata> constructors) {
        this.type = type;
        this.fields = fields == null ? Set.of() : fields;
        this.getters = getters == null ? Set.of() : getters;
        this.setters = setters == null ? Set.of() : setters;
        this.constructors = constructors == null ? Set.of() : constructors;
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

    public Set<ConstructorMetadata> getConstructors() {
        return constructors;
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
        private final boolean hostIsPublic;
        private final boolean record;
        private Set<FieldMetadata> fields;
        private Set<MethodMetadata> getters;
        private Set<MethodMetadata> setters;
        private Set<ConstructorMetadata> constructors;

        public Builder(ClassInfo modelClass, IndexView index) {
            this.packageName = modelClass.name().packagePrefix();
            this.type = modelClass.name().toString();
            ClassInfo hostClassInfo = hostClass(modelClass, index);
            this.host = hostClassInfo.name().toString();
            this.hostIsPublic = Modifier.isPublic(hostClassInfo.flags());
            this.record = modelClass.isRecord();
        }

        private static ClassInfo hostClass(ClassInfo modelClass, IndexView index) {
            ClassInfo curr = modelClass;
            while (!ClassInfo.NestingType.TOP_LEVEL.equals(curr.nestingType())) {
                curr = index.getClassByName(curr.enclosingClass());
            }
            return curr;
        }

        public Builder addField(FieldInfo field) {
            if (this.fields == null) {
                this.fields = new HashSet<>();
            }
            Type fieldType = field.type();
            this.fields.add(new FieldMetadata(field.name(), fieldType.descriptor(), fieldType.kind() == Type.Kind.PRIMITIVE,
                    field.declaringClass().name().toString(), host, record));

            return this;
        }

        public Builder addGetter(MethodInfo getter) {
            if (this.getters == null) {
                this.getters = new HashSet<>();
            }
            Type returnType = getter.returnType();
            this.getters.add(new MethodMetadata(getter.name(), returnType.descriptor(),
                    returnType.kind() == Type.Kind.PRIMITIVE, getter.declaringClass().name().toString(), host,
                    Modifier.isInterface(getter.declaringClass().flags())));

            return this;
        }

        public Builder addSetter(MethodInfo setter) {
            if (this.setters == null) {
                this.setters = new HashSet<>();
            }
            Type valueType = setter.parameterType(0);
            this.setters.add(new MethodMetadata(setter.name(), valueType.descriptor(), valueType.kind() == Type.Kind.PRIMITIVE,
                    setter.declaringClass().name().toString(), host,
                    Modifier.isInterface(setter.declaringClass().flags())));

            return this;
        }

        public Builder addConstructor(MethodInfo constructor) {
            if (this.constructors == null) {
                this.constructors = new HashSet<>();
            }
            String descriptor = constructor.descriptor();
            List<ParameterMetadata> parameterDescriptors = new ArrayList<>();
            for (MethodParameterInfo parameter : constructor.parameters()) {
                parameterDescriptors.add(new ParameterMetadata(parameter.nameOrDefault(), parameter.type().descriptor(),
                        parameter.type().kind() == Type.Kind.PRIMITIVE));
            }
            this.constructors.add(new ConstructorMetadata(
                    constructor.declaringClass().name().toString(), host, descriptor, parameterDescriptors));
            return this;
        }

        public Builder all(ClassInfo classToAccess) {
            for (FieldInfo field : classToAccess.fields()) {
                if (!Modifier.isStatic(field.flags())) {
                    addField(field);
                }
            }

            for (MethodInfo method : classToAccess.methods()) {
                if (method.isConstructor()) {
                    addConstructor(method);
                } else if (!Modifier.isStatic(method.flags())) {
                    if (method.parametersCount() == 0
                            && method.returnType().kind() != Type.Kind.VOID) {
                        addGetter(method);
                    }
                    if (method.parametersCount() == 1) {
                        addSetter(method);
                    }
                }
            }

            return this;
        }

        public HibernateAccessorBuildItem build() {
            return new HibernateAccessorBuildItem(new TypeMetadata(packageName, type, host, hostIsPublic), fields, getters,
                    setters,
                    constructors);
        }
    }

    public interface MemberMetadata {
        String name();

        String descriptor();

        boolean isPrimitive();

        String declaringClass();

        String host();
    }

    public record FieldMetadata(String name, String descriptor, boolean isPrimitive,
            String declaringClass, String host, boolean readOnly) implements MemberMetadata {
    }

    public record MethodMetadata(String name, String descriptor, boolean isPrimitive,
            String declaringClass, String host, boolean isInterface) implements MemberMetadata {
    }

    public record ConstructorMetadata(String declaringClass, String host, String descriptor,
            List<ParameterMetadata> parameters) {
    }

    public record ParameterMetadata(String name, String descriptor, boolean isPrimitive) {
    }

    public record TypeMetadata(String packageName, String name, String host,
            boolean isPublic) implements Comparable<TypeMetadata> {

        private static final Comparator<TypeMetadata> COMPARATOR = Comparator.comparing(TypeMetadata::packageName)
                .thenComparing(TypeMetadata::name);

        @Override
        public int compareTo(TypeMetadata o) {
            return COMPARATOR.compare(this, o);
        }
    }

}
