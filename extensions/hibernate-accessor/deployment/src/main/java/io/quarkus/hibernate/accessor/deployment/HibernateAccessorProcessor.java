package io.quarkus.hibernate.accessor.deployment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import io.quarkus.deployment.Feature;
import io.quarkus.deployment.GeneratedClassGizmo2Adaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.gizmo2.Gizmo;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.Builder;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.ConstructorMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.FieldMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.ReadField;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.ReadGetter;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.ReadMember;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.WriteField;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.WriteMember;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorHostClassFunction.WriteSetter;
import io.quarkus.hibernate.accessor.runtime.HibernateAccessorRecorder;
import io.quarkus.hibernate.accessor.runtime.ReflectionFreeAccessor;

/**
 * Main build-time processor for the Hibernate Accessor extension.
 * <p>
 * This extension eliminates runtime reflection for Hibernate entity property access by generating
 * optimized bytecode at build time. The pipeline works in three phases:
 * <ol>
 * <li>{@link #findExtraTypesToProcess} — scans the Jandex index for {@link ReflectionFreeAccessor} annotations
 * on fields, methods, and constructors, producing {@link HibernateAccessorBuildItem}s that describe what to generate.</li>
 * <li>{@link #generateDirectAccessors} — for each host class, injects static {@code $$__hibernateRead},
 * {@code $$__hibernateWrite}, and {@code $$__hibernateCreate} methods (via ASM bytecode transformation),
 * generates singleton reader/writer/instantiator implementations, bridge classes for non-public hosts,
 * and a central factory that maps reflection objects to their pre-generated accessors.</li>
 * <li>{@link #accessFActory} — records a static-init step that instantiates the generated factory at runtime.</li>
 * </ol>
 */
class HibernateAccessorProcessor {

    private static final DotName REFLECTION_FREE_ACCESSOR = DotName.createSimple(ReflectionFreeAccessor.class);

    @BuildStep
    void feature(BuildProducer<FeatureBuildItem> features) {
        features.produce(new FeatureBuildItem(Feature.HIBERNATE_ACCESSOR));
    }

    /**
     * Scans the Jandex index for all {@link ReflectionFreeAccessor} annotations and groups them
     * by declaring class. Fields become direct read/write accessors; zero-parameter methods become
     * getters; single-parameter methods become setters; constructors are recorded for instantiator generation.
     */
    @BuildStep
    void findExtraTypesToProcess(
            CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<HibernateAccessorBuildItem> accessorBuildItemBuildProducer) {
        Map<String, Builder> builders = new HashMap<>();

        IndexView index = combinedIndexBuildItem.getIndex();
        for (AnnotationInstance annotation : index.getAnnotations(REFLECTION_FREE_ACCESSOR)) {

            AnnotationTarget target = annotation.target();
            switch (target.kind()) {
                case FIELD -> builders.computeIfAbsent(target.asField().declaringClass().name().toString(),
                        modelClass -> new Builder(index.getClassByName(modelClass), index))
                        .addField(target.asField());
                case METHOD -> {
                    MethodInfo method = target.asMethod();
                    Builder builder = builders.computeIfAbsent(
                            method.declaringClass().name().toString(),
                            modelClass -> new Builder(index.getClassByName(modelClass), index));
                    if (method.isConstructor()) {
                        builder.addConstructor(method);
                    } else if (method.parametersCount() == 0) {
                        builder.addGetter(method);
                    } else if (method.parametersCount() == 1) {
                        builder.addSetter(method);
                    } else {
                        throw new UnsupportedOperationException(
                                "Methods with more than one parameter cannot be getters/setters. Method " + method
                                        + " cannot be processed.");
                    }
                }
                default -> throw new UnsupportedOperationException(
                        "Only fields, getters and setters can be annotated with " + REFLECTION_FREE_ACCESSOR);
            }
        }
        for (Builder builder : builders.values()) {
            accessorBuildItemBuildProducer.produce(builder.build());
        }

    }

    /**
     * Core generation step. Aggregates all {@link HibernateAccessorBuildItem}s per host class, then:
     * <ul>
     * <li>Injects {@code $$__hibernateRead/Write/Create} static methods into each host class via
     * {@link HibernateAccessorHostClassFunction} (ASM bytecode transformation).</li>
     * <li>Generates bridge classes for non-public hosts so the factory can reach them.</li>
     * <li>Generates singleton {@code HibernateAccessorValueReaderImpl}, {@code WriterImpl}, and
     * {@code InstantiatorImpl} classes that dispatch to the injected host methods via a two-level
     * switch: first on classIndex (which host), then on memberIndex (which field/method/ctor).</li>
     * <li>Generates {@code QuarkusHibernateAccessorFactory} that maps reflection {@code Field},
     * {@code Method}, and {@code Constructor} objects to the pre-generated accessor instances.</li>
     * </ul>
     */
    @BuildStep
    void generateDirectAccessors(
            List<HibernateAccessorBuildItem> hibernateAccessorBuildItemList,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<BytecodeTransformerBuildItem> transformer) {

        // Collects per-host-class data: readers, writers, constructors, and factory index entries.
        // Uses LinkedHashMap to preserve insertion order — classIndex values depend on iteration order.
        Map<String, HostData> hostDataMap = new LinkedHashMap<>();
        // Deduplicates members when the same field/method appears in multiple build items
        // (e.g. from both ORM and Envers scanning the same entity).
        Set<Object> processedMembers = new HashSet<>();
        String currentType = null;

        for (HibernateAccessorBuildItem accessItem : hibernateAccessorBuildItemList) {
            String host = accessItem.getType().host();
            HostData hostData = hostDataMap.computeIfAbsent(host, k -> new HostData());
            if (!accessItem.getType().isPublic()) {
                hostData.isPublic = false;
            }

            if (!accessItem.getType().name().equals(currentType)) {
                currentType = accessItem.getType().name();
                processedMembers.clear();
            }

            for (FieldMetadata field : accessItem.getFields()) {
                if (processedMembers.add(field)) {
                    int readerIdx = hostData.readers.size();
                    hostData.readers.add(new ReadField(field.declaringClass(), field.name(),
                            field.descriptor(), field.isPrimitive()));
                    hostData.factoryReaderFields.add(new FactoryEntry(
                            field.declaringClass(), "field", field.name(), readerIdx));

                    if (!field.readOnly()) {
                        int writerIdx = hostData.writers.size();
                        hostData.writers.add(new WriteField(field.declaringClass(), field.name(),
                                field.descriptor(), field.isPrimitive()));
                        hostData.factoryWriterFields.add(new FactoryEntry(
                                field.declaringClass(), "field", field.name(), writerIdx));
                    }
                }
            }

            for (MethodMetadata getter : accessItem.getGetters()) {
                if (processedMembers.add(getter)) {
                    if (getter.isInterface() && getter.declaringClass().equals(host)) {
                        hostData.isInterface = true;
                    }
                    int readerIdx = hostData.readers.size();
                    hostData.readers.add(new ReadGetter(getter.declaringClass(), getter.name(),
                            getter.descriptor(), getter.isPrimitive(), getter.isInterface()));
                    hostData.factoryReaderFields.add(new FactoryEntry(
                            getter.declaringClass(), "method", getter.name(), readerIdx));
                }
            }

            for (MethodMetadata setter : accessItem.getSetters()) {
                if (processedMembers.add(setter)) {
                    if (setter.isInterface() && setter.declaringClass().equals(host)) {
                        hostData.isInterface = true;
                    }
                    int writerIdx = hostData.writers.size();
                    hostData.writers.add(new WriteSetter(setter.declaringClass(), setter.name(),
                            setter.descriptor(), setter.isPrimitive(), setter.isInterface()));
                    hostData.factoryWriterFields.add(new FactoryEntry(
                            setter.declaringClass(), "method", setter.name(), writerIdx));
                }
            }

            for (ConstructorMetadata ctor : accessItem.getConstructors()) {
                if (processedMembers.add(ctor)) {
                    int ctorIdx = hostData.constructors.size();
                    hostData.constructors.add(ctor);
                    hostData.factoryCtorEntries.add(new FactoryCtorEntry(
                            ctor.declaringClass(), ctor.descriptor(), ctorIdx));
                }
            }
        }

        HibernateAccessorFactoryImplementation factoryImpl = new HibernateAccessorFactoryImplementation();
        HibernateAccessorBridgeGenerator bridgeGen = new HibernateAccessorBridgeGenerator();

        // These lists track which host classes have readers/writers/constructors.
        // Their indices become the classIndex values used in the generated switch dispatchers.
        List<String> readerHosts = new ArrayList<>();
        List<String> writerHosts = new ArrayList<>();
        List<String> instantiatorHosts = new ArrayList<>();
        Set<String> interfaceHosts = new HashSet<>();

        for (Map.Entry<String, HostData> entry : hostDataMap.entrySet()) {
            String host = entry.getKey();
            HostData data = entry.getValue();

            if (data.isInterface) {
                interfaceHosts.add(host);
            }

            // Non-public (package-private) classes can't be called from the generated singleton impls,
            // so we create a public bridge class in the same package that forwards calls.
            boolean needsBridge = !data.isPublic && !data.isInterface;
            String dispatchTarget = needsBridge ? HibernateAccessorBridgeGenerator.bridgeFqcn(host) : host;

            if (needsBridge) {
                generatedClasses.produce(new GeneratedClassBuildItem(true,
                        HibernateAccessorBridgeGenerator.bridgeFqcn(host),
                        bridgeGen.generate(host,
                                !data.readers.isEmpty(),
                                !data.writers.isEmpty(),
                                !data.constructors.isEmpty())));
            }

            int readerClassIndex = -1;
            if (!data.readers.isEmpty()) {
                readerClassIndex = readerHosts.size();
                readerHosts.add(dispatchTarget);
            }
            int writerClassIndex = -1;
            if (!data.writers.isEmpty()) {
                writerClassIndex = writerHosts.size();
                writerHosts.add(dispatchTarget);
            }
            int instantiatorClassIndex = -1;
            if (!data.constructors.isEmpty()) {
                instantiatorClassIndex = instantiatorHosts.size();
                instantiatorHosts.add(dispatchTarget);
            }

            for (FactoryEntry fe : data.factoryReaderFields) {
                factoryImpl.addReaderEntry(fe.declaringClass(), fe.type(), fe.name(),
                        readerClassIndex, fe.memberIndex());
            }
            for (FactoryEntry fe : data.factoryWriterFields) {
                factoryImpl.addWriterEntry(fe.declaringClass(), fe.type(), fe.name(),
                        writerClassIndex, fe.memberIndex());
            }
            for (FactoryCtorEntry fe : data.factoryCtorEntries) {
                factoryImpl.addInstantiatorEntry(fe.declaringClass(), fe.descriptor(),
                        instantiatorClassIndex, fe.ctorIndex());
            }

            transformer.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(host)
                    .setCacheable(true)
                    .setPriority(-2)
                    .setVisitorFunction(new HibernateAccessorHostClassFunction(
                            data.readers, data.writers, data.constructors))
                    .build());
        }

        HibernateAccessorSingleImplGenerator implGen = new HibernateAccessorSingleImplGenerator();

        if (!readerHosts.isEmpty()) {
            generatedClasses.produce(new GeneratedClassBuildItem(true,
                    HibernateAccessorSingleImplGenerator.READER_IMPL,
                    implGen.generateReaderImpl(readerHosts, interfaceHosts)));
        }
        if (!writerHosts.isEmpty()) {
            generatedClasses.produce(new GeneratedClassBuildItem(true,
                    HibernateAccessorSingleImplGenerator.WRITER_IMPL,
                    implGen.generateWriterImpl(writerHosts, interfaceHosts)));
        }
        if (!instantiatorHosts.isEmpty()) {
            generatedClasses.produce(new GeneratedClassBuildItem(true,
                    HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL,
                    implGen.generateInstantiatorImpl(instantiatorHosts, interfaceHosts)));
        }

        Gizmo classGizmo = Gizmo.create(new GeneratedClassGizmo2Adaptor(generatedClasses, null, true));
        factoryImpl.create(classGizmo);
    }

    /**
     * Registers the generated factory class for reflective instantiation (needed for native image).
     */
    @BuildStep
    void registerForReflection(
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(ReflectiveClassBuildItem
                .builder(HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY).constructors().build());
    }

    /**
     * Records a static-init step that instantiates the generated {@code QuarkusHibernateAccessorFactory}
     * at runtime and wraps it in a build item for consumption by the Hibernate ORM extension.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    HibernateAccessorFactoryBuildItem accessFActory(
            HibernateAccessorRecorder recorder) {
        return new HibernateAccessorFactoryBuildItem(
                recorder.createAccessorFactory(HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY));
    }

    /**
     * Accumulates all accessor metadata for a single host class (the top-level class
     * that inner/nested entity classes belong to). Tracks readers, writers, constructors,
     * and their corresponding factory index entries.
     */
    private static class HostData {
        final List<ReadMember> readers = new ArrayList<>();
        final List<WriteMember> writers = new ArrayList<>();
        final List<ConstructorMetadata> constructors = new ArrayList<>();
        final List<FactoryEntry> factoryReaderFields = new ArrayList<>();
        final List<FactoryEntry> factoryWriterFields = new ArrayList<>();
        final List<FactoryCtorEntry> factoryCtorEntries = new ArrayList<>();
        boolean isInterface;
        boolean isPublic = true;
    }

    // Maps a field or method to its position within its host class for the factory lookup key.
    private record FactoryEntry(String declaringClass, String type, String name, int memberIndex) {
    }

    // Maps a constructor to its position within its host class for the factory lookup key.
    private record FactoryCtorEntry(String declaringClass, String descriptor, int ctorIndex) {
    }
}
