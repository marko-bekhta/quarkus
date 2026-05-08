package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;

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
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBridgeGenerator.MethodForward;
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

class HibernateAccessorProcessor {

    private static final DotName REFLECTION_FREE_ACCESSOR = DotName.createSimple(ReflectionFreeAccessor.class);

    private static final String READER_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorValueReader";
    private static final String WRITER_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorValueWriter";
    private static final String INSTANTIATOR_INTERFACE_INTERNAL = "org/hibernate/accessor/HibernateAccessorInstantiator";

    @BuildStep
    void feature(BuildProducer<FeatureBuildItem> features) {
        features.produce(new FeatureBuildItem(Feature.HIBERNATE_ACCESSOR));
    }

    @BuildStep
    void findExtraTypesToProcess(
            CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<HibernateAccessorBuildItem> accessorBuildItemBuildProducer) {
        Map<String, Builder> builders = new HashMap<>();

        IndexView index = combinedIndexBuildItem.getIndex();
        for (AnnotationInstance annotation : index.getAnnotations(REFLECTION_FREE_ACCESSOR)) {

            final AnnotationTarget target = annotation.target();
            switch (target.kind()) {
                case CLASS -> builders.computeIfAbsent(target.asClass().name().toString(),
                        modelClass -> new Builder(target.asClass()).all(target.asClass()));
                case FIELD -> builders.computeIfAbsent(target.asField().declaringClass().name().toString(),
                        modelClass -> new Builder(index.getClassByName(modelClass)))
                        .addField(target.asField());
                case METHOD -> {
                    MethodInfo method = target.asMethod();
                    Builder builder = builders.computeIfAbsent(
                            method.declaringClass().name().toString(),
                            modelClass -> new Builder(index.getClassByName(modelClass)));
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

    @BuildStep
    void generateDirectAccessors(
            List<HibernateAccessorBuildItem> hibernateAccessorBuildItemList,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<BytecodeTransformerBuildItem> transformer) {

        Map<String, HostData> hostDataMap = new LinkedHashMap<>();
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
                    hostData.readers.add(new ReadField(field.declaringClass(), field.name(),
                            field.descriptor(), field.isPrimitive()));
                    hostData.hasFieldReaders = true;

                    if (!field.readOnly()) {
                        hostData.writers.add(new WriteField(field.declaringClass(), field.name(),
                                field.descriptor(), field.isPrimitive()));
                        hostData.hasFieldWriters = true;
                    }
                }
            }

            for (MethodMetadata getter : accessItem.getGetters()) {
                if (processedMembers.add(getter)) {
                    if (getter.isInterface() && getter.declaringClass().equals(host)) {
                        hostData.isInterface = true;
                    }
                    hostData.readers.add(new ReadGetter(getter.declaringClass(), getter.name(),
                            getter.descriptor(), getter.isPrimitive(), getter.isInterface(), getter.returnDescriptor()));
                    hostData.hasMethodReaders = true;
                }
            }

            for (MethodMetadata setter : accessItem.getSetters()) {
                if (processedMembers.add(setter)) {
                    if (setter.isInterface() && setter.declaringClass().equals(host)) {
                        hostData.isInterface = true;
                    }
                    hostData.writers.add(new WriteSetter(setter.declaringClass(), setter.name(),
                            setter.descriptor(), setter.isPrimitive(), setter.isInterface(), setter.returnDescriptor()));
                    hostData.hasMethodWriters = true;
                }
            }

            for (ConstructorMetadata ctor : accessItem.getConstructors()) {
                if (processedMembers.add(ctor)) {
                    hostData.constructors.add(ctor);
                }
            }
        }

        HibernateAccessorFactoryImplementation factoryImpl = new HibernateAccessorFactoryImplementation();
        HibernateAccessorBridgeGenerator bridgeGen = new HibernateAccessorBridgeGenerator();

        List<String> hosts = new ArrayList<>();
        Set<String> interfaceHosts = new HashSet<>();

        for (Map.Entry<String, HostData> entry : hostDataMap.entrySet()) {
            String host = entry.getKey();
            HostData data = entry.getValue();

            if (data.isInterface) {
                interfaceHosts.add(host);
            }

            boolean needsBridge = !data.isPublic && !data.isInterface;
            String dispatchTarget = needsBridge ? HibernateAccessorBridgeGenerator.bridgeFqcn(host) : host;
            String dispatchTargetInternal = fqcnToName(dispatchTarget);

            int classIndex = hosts.size();
            hosts.add(dispatchTarget);

            // Always register the host for all accessor types
            factoryImpl.registerDispatchTarget(host, dispatchTargetInternal, data.isInterface);
            factoryImpl.registerFieldReader(host);
            factoryImpl.registerMethodReader(host);
            factoryImpl.registerFieldWriter(host);
            factoryImpl.registerMethodWriter(host);
            factoryImpl.registerInstantiator(host);

            // Also register declaring classes of actual members (for inheritance)
            Set<String> registeredDCs = new HashSet<>();
            registeredDCs.add(host);
            for (ReadMember rm : data.readers) {
                String dc = rm.declaringClass();
                if (registeredDCs.add(dc)) {
                    factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.isInterface);
                }
                if (rm instanceof ReadField) {
                    factoryImpl.registerFieldReader(dc);
                } else {
                    factoryImpl.registerMethodReader(dc);
                }
            }
            for (WriteMember wm : data.writers) {
                String dc = wm.declaringClass();
                if (registeredDCs.add(dc)) {
                    factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.isInterface);
                }
                if (wm instanceof WriteField) {
                    factoryImpl.registerFieldWriter(dc);
                } else {
                    factoryImpl.registerMethodWriter(dc);
                }
            }
            for (ConstructorMetadata ctor : data.constructors) {
                String dc = ctor.declaringClass();
                if (registeredDCs.add(dc)) {
                    factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.isInterface);
                }
                factoryImpl.registerInstantiator(dc);
            }

            // Always include all accessor method forwards for bridge
            List<MethodForward> accessorMethods = List.of(
                    new MethodForward(
                            HibernateAccessorFactoryImplementation.FIELD_READER,
                            "(Ljava/lang/String;)L" + READER_INTERFACE_INTERNAL + ";"),
                    new MethodForward(
                            HibernateAccessorFactoryImplementation.METHOD_READER,
                            "(Ljava/lang/String;)L" + READER_INTERFACE_INTERNAL + ";"),
                    new MethodForward(
                            HibernateAccessorFactoryImplementation.FIELD_WRITER,
                            "(Ljava/lang/String;)L" + WRITER_INTERFACE_INTERNAL + ";"),
                    new MethodForward(
                            HibernateAccessorFactoryImplementation.METHOD_WRITER,
                            "(Ljava/lang/String;)L" + WRITER_INTERFACE_INTERNAL + ";"),
                    new MethodForward(
                            HibernateAccessorFactoryImplementation.INSTANTIATOR_ACCESSOR,
                            "(Ljava/lang/String;)L" + INSTANTIATOR_INTERFACE_INTERNAL + ";"));

            if (needsBridge) {
                generatedClasses.produce(new GeneratedClassBuildItem(true,
                        HibernateAccessorBridgeGenerator.bridgeFqcn(host),
                        bridgeGen.generate(host, true, true, true, accessorMethods)));
            }

            transformer.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(host)
                    .setCacheable(true)
                    .setPriority(-2)
                    .setVisitorFunction(new HibernateAccessorHostClassFunction(
                            data.readers, data.writers, data.constructors, classIndex))
                    .build());
        }

        HibernateAccessorSingleImplGenerator implGen = new HibernateAccessorSingleImplGenerator();

        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.READER_IMPL,
                implGen.generateReaderImpl(hosts, interfaceHosts)));
        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.WRITER_IMPL,
                implGen.generateWriterImpl(hosts, interfaceHosts)));
        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL,
                implGen.generateInstantiatorImpl(hosts, interfaceHosts)));

        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY,
                factoryImpl.generate()));
    }

    @BuildStep
    void registerForReflection(
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(ReflectiveClassBuildItem
                .builder(HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY).constructors().build());
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    HibernateAccessorFactoryBuildItem accessFActory(
            HibernateAccessorRecorder recorder) {
        recorder.initAccessorImplFactory(
                HibernateAccessorSingleImplGenerator.READER_IMPL,
                HibernateAccessorSingleImplGenerator.WRITER_IMPL,
                HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL);
        return new HibernateAccessorFactoryBuildItem(
                recorder.createAccessorFactory(HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY));
    }

    private static class HostData {
        final List<ReadMember> readers = new ArrayList<>();
        final List<WriteMember> writers = new ArrayList<>();
        final List<ConstructorMetadata> constructors = new ArrayList<>();
        boolean hasFieldReaders;
        boolean hasMethodReaders;
        boolean hasFieldWriters;
        boolean hasMethodWriters;
        boolean isInterface;
        boolean isPublic = true;
    }
}
