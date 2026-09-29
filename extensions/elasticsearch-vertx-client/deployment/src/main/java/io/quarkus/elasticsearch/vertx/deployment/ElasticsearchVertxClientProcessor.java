package io.quarkus.elasticsearch.vertx.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.elasticsearch.restclient.common.deployment.DevservicesElasticsearchBuildItem;
import io.quarkus.elasticsearch.vertx.ElasticsearchClientConfig;
import io.quarkus.elasticsearch.vertx.runtime.ElasticsearchVertxClientProducer;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

class ElasticsearchVertxClientProcessor {

    private static final DotName ELASTICSEARCH_CLIENT_CONFIG = DotName.createSimple(ElasticsearchClientConfig.class.getName());

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(Feature.ELASTICSEARCH_VERTX_CLIENT);
    }

    @BuildStep
    AdditionalBeanBuildItem build() {
        return AdditionalBeanBuildItem.unremovableOf(ElasticsearchVertxClientProducer.class);
    }

    @BuildStep
    void elasticsearchClientConfigSupport(BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<BeanDefiningAnnotationBuildItem> beanDefiningAnnotations) {
        // add the @ElasticsearchClientConfig class otherwise it won't be registered as a qualifier
        additionalBeans.produce(AdditionalBeanBuildItem.builder().addBeanClass(ElasticsearchClientConfig.class).build());

        beanDefiningAnnotations
                .produce(new BeanDefiningAnnotationBuildItem(ELASTICSEARCH_CLIENT_CONFIG, DotNames.APPLICATION_SCOPED, false));
    }

    @BuildStep
    HealthBuildItem addHealthCheck(ElasticsearchBuildTimeConfig buildTimeConfig) {
        return new HealthBuildItem("io.quarkus.elasticsearch.vertx.runtime.health.ElasticsearchHealthCheck",
                buildTimeConfig.healthEnabled());
    }

    @BuildStep
    DevservicesElasticsearchBuildItem devServices() {
        return new DevservicesElasticsearchBuildItem("quarkus.elasticsearch.hosts");
    }

}
