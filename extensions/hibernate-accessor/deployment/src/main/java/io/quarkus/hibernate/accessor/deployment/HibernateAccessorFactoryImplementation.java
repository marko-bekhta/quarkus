package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorPackageFactoryImplementation.PACKAGE_LEVEL_HIBERNATE_ACCESSOR_FACTORY;

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

class HibernateAccessorFactoryImplementation {

    static final String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";

    private final List<String> packages = new ArrayList<>();

    void addPackage(String packageName) {
        packages.add(packageName);
    }

    void create(io.quarkus.gizmo2.Gizmo classGizmo) {
        classGizmo.class_(QUARKUS_HIBERNATE_ACCESSOR_FACTORY, cc -> {
            cc.implements_(HibernateAccessorFactory.class);
            cc.public_();

            FieldDesc delegates = cc.field("delegates", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorFactory.class))));
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
                conc.public_();
                conc.body(bc -> {
                    bc.invokeSpecial(ConstructorDesc.of(cc.superClass()), conc.this_());
                    bc.set(this_.field(delegates), bc.new_(GenericType.of(HashMap.class,
                            List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorFactory.class)))));

                    for (String pkg : packages) {
                        String internalFactoryName = pkg + "." + PACKAGE_LEVEL_HIBERNATE_ACCESSOR_FACTORY;
                        ClassDesc factoryClass = ClassDesc.of(internalFactoryName);
                        bc.invokeInterface(
                                mapPut,
                                this_.field(delegates),
                                Const.of(pkg),
                                bc.getStaticField(FieldDesc.of(factoryClass, "INSTANCE", factoryClass)));
                    }
                    bc.return_();
                });
            });

            cc.method("instantiator", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorInstantiator.class);
                ParamVar constructor = mc.parameter("constructor", Constructor.class);
                mc.body(bc -> {
                    Expr delegate = lookupDelegate(bc, this_, delegates, mapGet, constructor,
                            MethodDesc.of(Constructor.class, "getDeclaringClass", Class.class));
                    bc.return_(bc.invokeInterface(
                            MethodDesc.of(HibernateAccessorFactory.class, "instantiator", HibernateAccessorInstantiator.class,
                                    Constructor.class),
                            delegate, constructor));
                });
            });

            cc.method("valueReader", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueReader.class);
                ParamVar field = mc.parameter("field", Field.class);
                mc.body(bc -> {
                    Expr delegate = lookupDelegate(bc, this_, delegates, mapGet, field,
                            MethodDesc.of(Field.class, "getDeclaringClass", Class.class));
                    bc.return_(bc.invokeInterface(
                            MethodDesc.of(HibernateAccessorFactory.class, "valueReader", HibernateAccessorValueReader.class,
                                    Field.class),
                            delegate, field));
                });
            });

            cc.method("valueReader", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueReader.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr delegate = lookupDelegate(bc, this_, delegates, mapGet, method,
                            MethodDesc.of(Method.class, "getDeclaringClass", Class.class));
                    bc.return_(bc.invokeInterface(
                            MethodDesc.of(HibernateAccessorFactory.class, "valueReader", HibernateAccessorValueReader.class,
                                    Method.class),
                            delegate, method));
                });
            });

            cc.method("valueWriter", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueWriter.class);
                ParamVar field = mc.parameter("field", Field.class);
                mc.body(bc -> {
                    Expr delegate = lookupDelegate(bc, this_, delegates, mapGet, field,
                            MethodDesc.of(Field.class, "getDeclaringClass", Class.class));
                    bc.return_(bc.invokeInterface(
                            MethodDesc.of(HibernateAccessorFactory.class, "valueWriter", HibernateAccessorValueWriter.class,
                                    Field.class),
                            delegate, field));
                });
            });

            cc.method("valueWriter", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueWriter.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr delegate = lookupDelegate(bc, this_, delegates, mapGet, method,
                            MethodDesc.of(Method.class, "getDeclaringClass", Class.class));
                    bc.return_(bc.invokeInterface(
                            MethodDesc.of(HibernateAccessorFactory.class, "valueWriter", HibernateAccessorValueWriter.class,
                                    Method.class),
                            delegate, method));
                });
            });
        });
    }

    private static Expr lookupDelegate(io.quarkus.gizmo2.creator.BlockCreator bc, This this_, FieldDesc delegates,
            MethodDesc mapGet, ParamVar member, MethodDesc getDeclaringClass) {
        Expr declClass = bc.invokeVirtual(getDeclaringClass, member);
        Expr packageName = bc.invokeVirtual(MethodDesc.of(Class.class, "getPackageName", String.class), declClass);

        LocalVar delegate = bc.localVar(
                "delegate",
                HibernateAccessorFactory.class,
                bc.invokeInterface(mapGet, this_.field(delegates), packageName));

        bc.if_(bc.isNull(delegate), b1 -> b1.throw_(IllegalStateException.class));
        return delegate;
    }
}
