package io.quarkus.hibernate.accessor.deployment;

import java.util.HashMap;
import java.util.HashSet;
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
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.FieldMetadata;
import io.quarkus.hibernate.accessor.deployment.HibernateAccessorBuildItem.MethodMetadata;
import io.quarkus.hibernate.accessor.runtime.HibernateAccessorRecorder;
import io.quarkus.hibernate.accessor.runtime.ReflectionFreeAccessor;

class HibernateAccessorProcessor {

    private static final DotName REFLECTION_FREE_ACCESSOR = DotName.createSimple(ReflectionFreeAccessor.class);

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
                    if (method.parametersCount() == 0) {
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
        Gizmo classGizmo = Gizmo.create(new GeneratedClassGizmo2Adaptor(generatedClasses, null, true));

        //  note: build items are comparable so they should all come sorted (in "groups" by type),
        //   leverage that instead of one huge map to find "duplicates"
        HibernateAccessorFactoryImplementation factoryImplementation = new HibernateAccessorFactoryImplementation();
        HibernateAccessorPackageFactoryImplementation packageFactoryImplementation = null;
        String currentPackage = null;
        String currentType = null;
        Set<Object> processedMembers = new HashSet<>();
        for (HibernateAccessorBuildItem accessItem : hibernateAccessorBuildItemList) {
            if (!accessItem.getType().packageName().equals(currentPackage)) {
                currentPackage = accessItem.getType().packageName();
                if (packageFactoryImplementation != null) {
                    packageFactoryImplementation.create(classGizmo);
                }
                packageFactoryImplementation = new HibernateAccessorPackageFactoryImplementation(currentPackage);
                factoryImplementation.addPackage(currentPackage);
            }
            if (!accessItem.getType().name().equals(currentType)) {
                currentType = accessItem.getType().name();
                processedMembers.clear();
            }
            for (FieldMetadata field : accessItem.getFields()) {
                if (processedMembers.add(field)) {
                    packageFactoryImplementation.add(field);

                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().host())
                            .setCacheable(true)
                            .setPriority(-2)
                            .setVisitorFunction(new HibernateAccessorFieldFunction(field))
                            .build());

                    var implementation = new HibernateAccessorFieldImplementation(field, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(true, implementation.getReaderName(),
                            implementation.generateReaderBytes()));
                    if (!field.readOnly()) {
                        generatedClasses.produce(new GeneratedClassBuildItem(true, implementation.getWriterName(),
                                implementation.generateWriterBytes()));
                    }
                }
            }

            for (MethodMetadata getter : accessItem.getGetters()) {
                if (processedMembers.add(getter)) {
                    packageFactoryImplementation.addReader(getter);
                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().host())
                            .setCacheable(true)
                            .setPriority(-2)
                            .setVisitorFunction(new HibernateAccessorGetterFunction(getter))
                            .build());

                    var implementation = new HibernateAccessorGetterImplementation(getter, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(true, implementation.getReaderName(),
                            implementation.generateReaderBytes()));
                }
            }

            for (MethodMetadata setter : accessItem.getSetters()) {
                if (processedMembers.add(setter)) {
                    packageFactoryImplementation.addWriter(setter);
                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().host())
                            .setCacheable(true)
                            .setPriority(-2)
                            .setVisitorFunction(new HibernateAccessorSetterFunction(setter))
                            .build());

                    var implementation = new HibernateAccessorSetterImplementation(setter, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(true, implementation.getWriterName(),
                            implementation.generateWriterBytes()));
                }
            }
        }
        if (packageFactoryImplementation != null) {
            packageFactoryImplementation.create(classGizmo);
        }
        factoryImplementation.create(classGizmo);
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
        return new HibernateAccessorFactoryBuildItem(
                recorder.createAccessorFactory(HibernateAccessorFactoryImplementation.QUARKUS_HIBERNATE_ACCESSOR_FACTORY));
    }

}
