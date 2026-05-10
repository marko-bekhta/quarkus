package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.*;
import static io.quarkus.hibernate.accessor.deployment.HibernateAccessorGenerationUtil.fqcnToName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MemberMetadata;
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

        List<HostData> hosts = new ArrayList<>();
        HostData currentType = null;

        for (HibernateAccessorBuildItem accessItem : hibernateAccessorBuildItemList) {
            if (currentType == null || !accessItem.getType().name().equals(currentType.type().name())) {
                currentType = new HostData(accessItem.getType());
                hosts.add(currentType);
            }

            for (FieldMetadata field : accessItem.getFields()) {
                currentType.readers.add(field);

                if (!field.readOnly()) {
                    currentType.writers.add(field);
                }
            }

            currentType.readers.addAll(accessItem.getGetters());
            currentType.writers.addAll(accessItem.getSetters());
            currentType.constructors.addAll(accessItem.getConstructors());
        }

        HibernateAccessorFactoryImplementation factoryImpl = new HibernateAccessorFactoryImplementation();
        HibernateAccessorBridgeGenerator bridgeGen = new HibernateAccessorBridgeGenerator();

        int currentClassIndex = 0;
        for (HostData data : hosts) {
            boolean needsBridge = !data.type().isPublic() && !data.type().isInterface();
            String dispatchTarget = needsBridge ? HibernateAccessorBridgeGenerator.bridgeFqcn(data.type().host())
                    : data.type().host();
            String dispatchTargetInternal = fqcnToName(dispatchTarget);

            // hosts.add(dispatchTarget);

            // Always register the host for all accessor types
            factoryImpl.registerDispatchTarget(data.type().host(), dispatchTargetInternal, data.type().isInterface());
            factoryImpl.registerFieldReader(data.type().host());
            factoryImpl.registerMethodReader(data.type().host());
            factoryImpl.registerFieldWriter(data.type().host());
            factoryImpl.registerMethodWriter(data.type().host());
            factoryImpl.registerInstantiator(data.type().host());

            // Also register declaring classes of actual members (for inheritance)
            for (MemberMetadata rm : data.readers) {
                String dc = rm.declaringClass();
                factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.type().isInterface());
                if (rm instanceof FieldMetadata) {
                    factoryImpl.registerFieldReader(dc);
                } else {
                    factoryImpl.registerMethodReader(dc);
                }
            }
            for (MemberMetadata wm : data.writers) {
                String dc = wm.declaringClass();
                factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.type().isInterface());
                if (wm instanceof FieldMetadata) {
                    factoryImpl.registerFieldWriter(dc);
                } else {
                    factoryImpl.registerMethodWriter(dc);
                }
            }
            for (ConstructorMetadata ctor : data.constructors) {
                String dc = ctor.declaringClass();
                factoryImpl.registerDispatchTarget(dc, dispatchTargetInternal, data.type().isInterface());
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
                        HibernateAccessorBridgeGenerator.bridgeFqcn(data.type().host()),
                        bridgeGen.generate(data.type().host(), true, true, true, accessorMethods)));
            }

            transformer.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(data.type().host())
                    .setCacheable(true)
                    // We need to make sure that we run this transformation
                    // *after* Hibernate ORM and Panache are done with their changes:
                    .setPriority(HibernateAccessorHostClassFunction.ACCESSOR_TRANSFORMATION_PRIORITY)
                    .setVisitorFunction(new HibernateAccessorHostClassFunction(
                            data.readers, data.writers, data.constructors, currentClassIndex++))
                    .build());
        }

        HibernateAccessorSingleImplGenerator implGen = new HibernateAccessorSingleImplGenerator();

        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.READER_IMPL,
                implGen.generateReaderImpl(hosts)));
        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.WRITER_IMPL,
                implGen.generateWriterImpl(hosts)));
        generatedClasses.produce(new GeneratedClassBuildItem(true,
                HibernateAccessorSingleImplGenerator.INSTANTIATOR_IMPL,
                implGen.generateInstantiatorImpl(hosts)));

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

    record HostData(TypeMetadata type,
            Set<MemberMetadata> readers,
            Set<MemberMetadata> writers,
            Set<ConstructorMetadata> constructors) {

        private HostData(TypeMetadata type) {
            this(type, new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
    }
}
