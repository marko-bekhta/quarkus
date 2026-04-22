package io.quarkus.hibernate.accessor.deployment;

import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.Builder;
import static io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem.FieldMetadata;

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
import org.jboss.logging.Logger;

import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.hibernate.accessor.runtime.ReflectionFreeAccessor;
import io.quarkus.hibernate.accessor.spi.HibernateAccessorBuildItem;

class HibernateAccessorProcessor {

    private static final Logger LOG = Logger.getLogger(HibernateAccessorProcessor.class);

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
                        builder.addGetter(method.name());
                    } else if (method.parametersCount() == 1) {
                        builder.addSetter(method.name());
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
        // TODO: handle same types contributed multiple types
        //  note: build item is comparable so they should all come in groups leverage that instead of one huge map?
        String currentType = null;
        Set<Object> processedMembers = new HashSet<>();
        for (HibernateAccessorBuildItem accessItem : hibernateAccessorBuildItemList) {
            if (!accessItem.getType().name().equals(currentType)) {
                currentType = accessItem.getType().name();
                processedMembers.clear();
            }
            for (FieldMetadata field : accessItem.getFields()) {
                if (processedMembers.add(field)) {
                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().host())
                            .setCacheable(true)
                            .setVisitorFunction(new HibernateAccessorFieldFunction(field))
                            .build());

                    var implementation = new HibernateAccessorFieldImplementation(field, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(false, implementation.getReaderName(),
                            implementation.generateReaderBytes()));
                    generatedClasses.produce(new GeneratedClassBuildItem(false, implementation.getWriterName(),
                            implementation.generateWriterBytes()));
                }
            }

            for (String getter : accessItem.getGetters()) {
                if (processedMembers.add(getter)) {
                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().name())
                            .setCacheable(true)
                            .setVisitorFunction(new HibernateAccessorGetterFunction(getter))
                            .build());

                    var implementation = new HibernateAccessorGetterImplementation(getter, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(false, implementation.getReaderName(),
                            implementation.generateReaderBytes()));
                }
            }

            for (String setter : accessItem.getSetters()) {
                if (processedMembers.add(setter)) {
                    transformer.produce(new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(accessItem.getType().name())
                            .setCacheable(true)
                            .setVisitorFunction(new HibernateAccessorSetterFunction(setter))
                            .build());

                    var implementation = new HibernateAccessorSetterImplementation(setter, accessItem.getType());

                    generatedClasses.produce(new GeneratedClassBuildItem(false, implementation.getWriterName(),
                            implementation.generateWriterBytes()));
                }
            }
        }
    }
}
