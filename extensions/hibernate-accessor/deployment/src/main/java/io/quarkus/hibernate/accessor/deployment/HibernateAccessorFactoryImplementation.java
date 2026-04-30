package io.quarkus.hibernate.accessor.deployment;

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
import io.quarkus.gizmo2.desc.ClassMethodDesc;
import io.quarkus.gizmo2.desc.ConstructorDesc;
import io.quarkus.gizmo2.desc.FieldDesc;
import io.quarkus.gizmo2.desc.MethodDesc;

class HibernateAccessorFactoryImplementation {

    static final String QUARKUS_HIBERNATE_ACCESSOR_FACTORY = "io.quarkus.hibernate.accessor.runtime.QuarkusHibernateAccessorFactory";

    private static final String KEY_FORMAT = "%s.%s.%s";
    private static final String TYPE_FIELD = "field";
    private static final String TYPE_METHOD = "method";
    private static final String TYPE_CONSTRUCTOR = "constructor";

    private static final int INIT_BATCH_SIZE = 500;

    private final List<IndexEntry> readerEntries = new ArrayList<>();
    private final List<IndexEntry> writerEntries = new ArrayList<>();
    private final List<IndexEntry> instantiatorEntries = new ArrayList<>();

    void addReaderEntry(String declaringClass, String type, String name, int classIndex, int memberIndex) {
        readerEntries.add(new IndexEntry(
                KEY_FORMAT.formatted(declaringClass, type, name), classIndex, memberIndex));
    }

    void addWriterEntry(String declaringClass, String type, String name, int classIndex, int memberIndex) {
        writerEntries.add(new IndexEntry(
                KEY_FORMAT.formatted(declaringClass, type, name), classIndex, memberIndex));
    }

    void addInstantiatorEntry(String declaringClass, String descriptor, int classIndex, int ctorIndex) {
        instantiatorEntries.add(new IndexEntry(
                KEY_FORMAT.formatted(declaringClass, TYPE_CONSTRUCTOR, descriptor), classIndex, ctorIndex));
    }

    void addFieldReader(String declaringClass, String fieldName, int classIndex, int memberIndex) {
        addReaderEntry(declaringClass, TYPE_FIELD, fieldName, classIndex, memberIndex);
    }

    void addFieldWriter(String declaringClass, String fieldName, int classIndex, int memberIndex) {
        addWriterEntry(declaringClass, TYPE_FIELD, fieldName, classIndex, memberIndex);
    }

    void addMethodReader(String declaringClass, String methodName, int classIndex, int memberIndex) {
        addReaderEntry(declaringClass, TYPE_METHOD, methodName, classIndex, memberIndex);
    }

    void addMethodWriter(String declaringClass, String methodName, int classIndex, int memberIndex) {
        addWriterEntry(declaringClass, TYPE_METHOD, methodName, classIndex, memberIndex);
    }

    void create(io.quarkus.gizmo2.Gizmo classGizmo) {
        ClassDesc readerImplClass = ClassDesc.of(HibernateAccessorSingleImplGenerator.READER_IMPL);
        ClassDesc writerImplClass = ClassDesc.of(HibernateAccessorSingleImplGenerator.WRITER_IMPL);
        ClassDesc instantiatorImplClass = ClassDesc.of(HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL);
        ClassDesc factoryClassDesc = ClassDesc.of(QUARKUS_HIBERNATE_ACCESSOR_FACTORY);

        classGizmo.class_(QUARKUS_HIBERNATE_ACCESSOR_FACTORY, cc -> {
            cc.implements_(HibernateAccessorFactory.class);
            cc.public_();

            FieldDesc readers = cc.field("readers", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueReader.class))));
            });
            FieldDesc writers = cc.field("writers", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorValueWriter.class))));
            });
            FieldDesc instantiators = cc.field("instantiators", fc -> {
                fc.private_();
                fc.final_();
                fc.setType(GenericType.of(Map.class,
                        List.of(TypeArgument.of(String.class), TypeArgument.of(HibernateAccessorInstantiator.class))));
            });

            final MethodDesc mapPut;
            final MethodDesc mapGet;
            try {
                mapPut = MethodDesc.of(Map.class.getDeclaredMethod("put", Object.class, Object.class));
                mapGet = MethodDesc.of(Map.class.getDeclaredMethod("get", Object.class));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }

            List<String> readerInitMethods = generateBatchedInitMethods(cc, "initReaders", readerEntries,
                    readerImplClass, factoryClassDesc, mapPut);
            List<String> writerInitMethods = generateBatchedInitMethods(cc, "initWriters", writerEntries,
                    writerImplClass, factoryClassDesc, mapPut);
            List<String> instantiatorInitMethods = generateBatchedInitMethods(cc, "initInstantiators",
                    instantiatorEntries, instantiatorImplClass, factoryClassDesc, mapPut);

            This this_ = cc.this_();
            cc.constructor(conc -> {
                conc.public_();
                conc.body(bc -> {
                    bc.invokeSpecial(ConstructorDesc.of(cc.superClass()), conc.this_());

                    bc.set(this_.field(readers), bc.new_(GenericType.of(HashMap.class,
                            List.of(TypeArgument.of(String.class),
                                    TypeArgument.of(HibernateAccessorValueReader.class)))));
                    bc.set(this_.field(writers), bc.new_(GenericType.of(HashMap.class,
                            List.of(TypeArgument.of(String.class),
                                    TypeArgument.of(HibernateAccessorValueWriter.class)))));
                    bc.set(this_.field(instantiators), bc.new_(GenericType.of(HashMap.class,
                            List.of(TypeArgument.of(String.class),
                                    TypeArgument.of(HibernateAccessorInstantiator.class)))));

                    ClassDesc mapDesc = ClassDesc.of(Map.class.getName());
                    ClassDesc voidDesc = ClassDesc.ofDescriptor("V");
                    for (String methodName : readerInitMethods) {
                        bc.invokeStatic(ClassMethodDesc.of(factoryClassDesc, methodName,
                                voidDesc, mapDesc), this_.field(readers));
                    }
                    for (String methodName : writerInitMethods) {
                        bc.invokeStatic(ClassMethodDesc.of(factoryClassDesc, methodName,
                                voidDesc, mapDesc), this_.field(writers));
                    }
                    for (String methodName : instantiatorInitMethods) {
                        bc.invokeStatic(ClassMethodDesc.of(factoryClassDesc, methodName,
                                voidDesc, mapDesc), this_.field(instantiators));
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
                    Expr key = bc.invokeVirtual(
                            MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                            Const.of(KEY_FORMAT),
                            bc.newArray(Object.class, className, Const.of(TYPE_CONSTRUCTOR), descriptor));
                    LocalVar inst = bc.localVar("inst", HibernateAccessorInstantiator.class,
                            bc.invokeInterface(mapGet, this_.field(instantiators), key));
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
                    Expr declClass = bc.invokeVirtual(
                            MethodDesc.of(Field.class, "getDeclaringClass", Class.class), field);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Field.class, "getName", String.class), field);
                    Expr key = bc.invokeVirtual(
                            MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                            Const.of(KEY_FORMAT),
                            bc.newArray(Object.class, className, Const.of(TYPE_FIELD), memberName));
                    LocalVar reader = bc.localVar("reader", HibernateAccessorValueReader.class,
                            bc.invokeInterface(mapGet, this_.field(readers), key));
                    bc.ifElse(
                            bc.isNull(reader),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(reader));
                });
            });

            cc.method("valueReader", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueReader.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(
                            MethodDesc.of(Method.class, "getDeclaringClass", Class.class), method);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Method.class, "getName", String.class), method);
                    Expr key = bc.invokeVirtual(
                            MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                            Const.of(KEY_FORMAT),
                            bc.newArray(Object.class, className, Const.of(TYPE_METHOD), memberName));
                    LocalVar reader = bc.localVar("reader", HibernateAccessorValueReader.class,
                            bc.invokeInterface(mapGet, this_.field(readers), key));
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
                    Expr declClass = bc.invokeVirtual(
                            MethodDesc.of(Field.class, "getDeclaringClass", Class.class), field);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Field.class, "getName", String.class), field);
                    Expr key = bc.invokeVirtual(
                            MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                            Const.of(KEY_FORMAT),
                            bc.newArray(Object.class, className, Const.of(TYPE_FIELD), memberName));
                    LocalVar writer = bc.localVar("writer", HibernateAccessorValueWriter.class,
                            bc.invokeInterface(mapGet, this_.field(writers), key));
                    bc.ifElse(
                            bc.isNull(writer),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(writer));
                });
            });

            cc.method("valueWriter", mc -> {
                mc.public_();
                mc.returning(HibernateAccessorValueWriter.class);
                ParamVar method = mc.parameter("method", Method.class);
                mc.body(bc -> {
                    Expr declClass = bc.invokeVirtual(
                            MethodDesc.of(Method.class, "getDeclaringClass", Class.class), method);
                    Expr className = bc.invokeVirtual(MethodDesc.of(Class.class, "getName", String.class), declClass);
                    Expr memberName = bc.invokeVirtual(MethodDesc.of(Method.class, "getName", String.class), method);
                    Expr key = bc.invokeVirtual(
                            MethodDesc.of(String.class, "formatted", String.class, Object[].class),
                            Const.of(KEY_FORMAT),
                            bc.newArray(Object.class, className, Const.of(TYPE_METHOD), memberName));
                    LocalVar writer = bc.localVar("writer", HibernateAccessorValueWriter.class,
                            bc.invokeInterface(mapGet, this_.field(writers), key));
                    bc.ifElse(
                            bc.isNull(writer),
                            b1 -> b1.throw_(UnsupportedOperationException.class),
                            b1 -> b1.return_(writer));
                });
            });
        });
    }

    private static List<String> generateBatchedInitMethods(
            io.quarkus.gizmo2.creator.ClassCreator cc,
            String baseName,
            List<IndexEntry> entries,
            ClassDesc implClass,
            ClassDesc factoryClassDesc,
            MethodDesc mapPut) {
        List<String> methodNames = new ArrayList<>();
        if (entries.isEmpty()) {
            return methodNames;
        }

        for (int batch = 0; batch * INIT_BATCH_SIZE < entries.size(); batch++) {
            int start = batch * INIT_BATCH_SIZE;
            int end = Math.min(start + INIT_BATCH_SIZE, entries.size());
            List<IndexEntry> chunk = entries.subList(start, end);
            String methodName = baseName + batch;
            methodNames.add(methodName);

            cc.staticMethod(methodName, mc -> {
                mc.private_();
                ParamVar map = mc.parameter("map", Map.class);
                mc.body(bc -> {
                    for (IndexEntry entry : chunk) {
                        bc.invokeInterface(mapPut, map,
                                Const.of(entry.key()),
                                bc.new_(implClass, Const.of(entry.classIndex()), Const.of(entry.memberIndex())));
                    }
                    bc.return_();
                });
            });
        }
        return methodNames;
    }

    record IndexEntry(String key, int classIndex, int memberIndex) {
    }
}
