package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.FieldMetadata;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.accessorFqcn;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.fieldWriterClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.instantiatorClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodReaderClassName;
import static io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.methodWriterClassName;

import java.lang.constant.ClassDesc;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.accessor.HibernateAccessorFactory;
import org.hibernate.accessor.HibernateAccessorInstantiator;
import org.hibernate.accessor.HibernateAccessorValueReader;
import org.hibernate.accessor.HibernateAccessorValueWriter;

import io.quarkus.gizmo2.Const;
import io.quarkus.gizmo2.Expr;
import io.quarkus.gizmo2.GenericType;
import io.quarkus.gizmo2.LocalVar;
import io.quarkus.gizmo2.ParamVar;
import io.quarkus.gizmo2.This;
import io.quarkus.gizmo2.TypeArgument;
import io.quarkus.gizmo2.desc.ConstructorDesc;
import io.quarkus.gizmo2.desc.FieldDesc;
import io.quarkus.gizmo2.desc.MethodDesc;

class HibernateAccessorPackageFactoryImplementation {

    static final String PACKAGE_LEVEL_HIBERNATE_ACCESSOR_FACTORY = "____HibernateAccessorFactory";
    private static final String KEY_FORMAT = "%s.%s.%s";
    private static final String TYPE_FIELD = "field";
    private static final String TYPE_METHOD = "method";
    private static final String TYPE_CONSTRUCTOR = "constructor";
    private final String packageName;
    private final List<FieldMetadata> fields;

    private final List<MethodMetadata> methodReaders;
    private final List<MethodMetadata> methodWriters;
    private final List<ConstructorMetadata> constructors;

    public HibernateAccessorPackageFactoryImplementation(String packageName) {
        this.packageName = packageName;
        this.fields = new ArrayList<>();
        this.methodReaders = new ArrayList<>();
        this.methodWriters = new ArrayList<>();
        this.constructors = new ArrayList<>();
    }

    public void add(FieldMetadata field) {
        fields.add(field);
    }

    public void addReader(MethodMetadata getter) {
        methodReaders.add(getter);
    }

    public void addWriter(MethodMetadata setter) {
        methodWriters.add(setter);
    }

    public void addInstantiator(ConstructorMetadata constructor) {
        constructors.add(constructor);
    }

    public void create(io.quarkus.gizmo2.Gizmo classGizmo) {
        String factoryClassName = packageName + "." + PACKAGE_LEVEL_HIBERNATE_ACCESSOR_FACTORY;
        classGizmo.class_(factoryClassName, cc -> {
            cc.implements_(HibernateAccessorFactory.class);
            cc.public_();
            cc.final_();

            cc.staticField("INSTANCE", sfc -> {
                sfc.public_();
                sfc.final_();

                ClassDesc factoryClass = ClassDesc.of(factoryClassName);
                sfc.setType(factoryClass);

                sfc.setInitializer(bc -> bc.yield(bc.new_(factoryClass)));
            });

            FieldDesc readers = cc.field("readers", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueReader.class))));
                fc.setInitializer(bc -> bc.yield(bc.new_(GenericType.of(HashMap.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueReader.class))))));
            });
            FieldDesc writers = cc.field("writers", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueWriter.class))));
                fc.setInitializer(bc -> bc.yield(bc.new_(GenericType.of(HashMap.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueWriter.class))))));
            });
            FieldDesc instantiators = cc.field("instantiators", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorInstantiator.class))));
                fc.setInitializer(bc -> bc.yield(bc.new_(GenericType.of(HashMap.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorInstantiator.class))))));
            });

            final MethodDesc mapPut;
            final MethodDesc mapGet;
            try {
                mapPut = MethodDesc.of(Map.class.getDeclaredMethod("put", Object.class, Object.class));
                mapGet = MethodDesc.of(Map.class.getDeclaredMethod("get", Object.class));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }

            This this_ = cc.this_();
            cc.constructor(conc -> {
                conc.private_();
                conc.body(bc -> {
                    bc.invokeSpecial(ConstructorDesc.of(cc.superClass()), conc.this_());
                    // bc.set(this_.field(readers), bc.new_(HashMap.class));

                    for (FieldMetadata f : fields) {
                        ClassDesc accessorClass = ClassDesc
                                .of(accessorFqcn(f.host(), fieldReaderClassName(f.declaringClass(), f.host(), f.name())));
                        bc.invokeInterface(
                                mapPut,
                                this_.field(readers),
                                Const.of(KEY_FORMAT.formatted(f.declaringClass(), TYPE_FIELD, f.name())),
                                bc.getStaticField(FieldDesc.of(accessorClass, "INSTANCE", accessorClass)));
                        if (!f.readOnly()) {
                            accessorClass = ClassDesc
                                    .of(accessorFqcn(f.host(),
                                            fieldWriterClassName(f.declaringClass(), f.host(), f.name())));
                            bc.invokeInterface(
                                    mapPut,
                                    this_.field(writers),
                                    Const.of(KEY_FORMAT.formatted(f.declaringClass(), TYPE_FIELD, f.name())),
                                    bc.getStaticField(FieldDesc.of(accessorClass, "INSTANCE", accessorClass)));
                        }
                    }

                    for (MethodMetadata m : methodReaders) {
                        ClassDesc accessorClass = ClassDesc
                                .of(accessorFqcn(m.host(), methodReaderClassName(m.declaringClass(), m.host(), m.name())));
                        bc.invokeInterface(
                                mapPut,
                                this_.field(readers),
                                Const.of(KEY_FORMAT.formatted(m.declaringClass(), TYPE_METHOD, m.name())),
                                bc.getStaticField(FieldDesc.of(accessorClass, "INSTANCE", accessorClass)));
                    }
                    for (MethodMetadata m : methodWriters) {
                        ClassDesc accessorClass = ClassDesc
                                .of(accessorFqcn(m.host(), methodWriterClassName(m.declaringClass(), m.host(), m.name())));
                        bc.invokeInterface(
                                mapPut,
                                this_.field(writers),
                                Const.of(KEY_FORMAT.formatted(m.declaringClass(), TYPE_METHOD, m.name())),
                                bc.getStaticField(FieldDesc.of(accessorClass, "INSTANCE", accessorClass)));
                    }

                    for (ConstructorMetadata c : constructors) {
                        ClassDesc accessorClass = ClassDesc
                                .of(accessorFqcn(c.host(),
                                        instantiatorClassName(c.declaringClass(), c.host(), c.descriptor())));
                        bc.invokeInterface(
                                mapPut,
                                this_.field(instantiators),
                                Const.of(KEY_FORMAT.formatted(c.declaringClass(), TYPE_CONSTRUCTOR, c.descriptor())),
                                bc.getStaticField(FieldDesc.of(accessorClass, "INSTANCE", accessorClass)));
                    }
                    bc.return_();
                });
            });

            cc.method("instantiator", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorInstantiator.class);
                ParamVar constructor = mc.parameter("constructor", Constructor.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(
                            MethodDesc.of(Constructor.class, "getDeclaringClass", Class.class), constructor);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr descriptor = bc.invokeStatic(
                            MethodDesc.of(
                                    io.quarkus.hibernate.accessor.runtime.spi.NamingUtil.class,
                                    "constructorDescriptor", String.class, Constructor.class),
                            constructor);

                    LocalVar inst = bc.localVar(
                            "inst",
                            HibernateAccessorInstantiator.class,
                            bc.invokeInterface(
                                    mapGet,
                                    this_.field(instantiators),
                                    bc.invokeVirtual(MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                                            Const.of(KEY_FORMAT),
                                            bc.newArray(Object.class, className, Const.of(TYPE_CONSTRUCTOR), descriptor))));

                    bc.ifElse(
                            bc.isNull(inst),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(inst));
                });
            });

            cc.method("valueReader", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueReader.class);
                ParamVar field = mc.parameter("field", Field.class);
                mc.body(bc -> {

                    Expr declClass = bc.invokeVirtual(MethodDesc.of(Field.class, "getDeclaringClass", Class.class), field);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Field.class, "getName", String.class), field);

                    LocalVar reader = bc.localVar(
                            "reader",
                            HibernateAccessorValueReader.class,
                            bc.invokeInterface(
                                    mapGet,
                                    this_.field(readers),
                                    bc.invokeVirtual(MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                                            Const.of(KEY_FORMAT),
                                            bc.newArray(Object.class, className, Const.of(TYPE_FIELD), memberName))));

                    bc.ifElse(
                            bc.isNull(reader),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(reader));
                });
            });

            cc.method("valueWriter", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueWriter.class);
                ParamVar field = mc.parameter("field", Field.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(MethodDesc.of(Field.class, "getDeclaringClass", Class.class), field);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Field.class, "getName", String.class), field);

                    LocalVar writer = bc.localVar(
                            "writer",
                            HibernateAccessorValueWriter.class,
                            bc.invokeInterface(
                                    mapGet,
                                    this_.field(writers),
                                    bc.invokeVirtual(MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                                            Const.of(KEY_FORMAT),
                                            bc.newArray(Object.class, className, Const.of(TYPE_FIELD), memberName))));

                    bc.ifElse(
                            bc.isNull(writer),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(writer));
                });
            });

            cc.method("valueReader", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueReader.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(MethodDesc.of(Method.class, "getDeclaringClass", Class.class), method);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Method.class, "getName", String.class), method);

                    LocalVar reader = bc.localVar(
                            "reader",
                            HibernateAccessorValueReader.class,
                            bc.invokeInterface(
                                    mapGet,
                                    this_.field(readers),
                                    bc.invokeVirtual(MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                                            Const.of(KEY_FORMAT),
                                            bc.newArray(Object.class, className, Const.of(TYPE_METHOD), memberName))));

                    bc.ifElse(
                            bc.isNull(reader),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(reader));
                });
            });

            cc.method("valueWriter", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueWriter.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(MethodDesc.of(Method.class, "getDeclaringClass", Class.class), method);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Method.class, "getName", String.class), method);

                    LocalVar writer = bc.localVar(
                            "writer",
                            HibernateAccessorValueWriter.class,
                            bc.invokeInterface(
                                    mapGet,
                                    this_.field(writers),
                                    bc.invokeVirtual(MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                                            Const.of(KEY_FORMAT),
                                            bc.newArray(Object.class, className, Const.of(TYPE_METHOD), memberName))));

                    bc.ifElse(
                            bc.isNull(writer),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(writer));
                });
            });
        });
    }
}
