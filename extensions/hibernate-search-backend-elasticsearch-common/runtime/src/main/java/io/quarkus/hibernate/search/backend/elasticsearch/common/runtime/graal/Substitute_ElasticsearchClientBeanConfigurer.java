package io.quarkus.hibernate.search.backend.elasticsearch.common.runtime.graal;

import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.TargetClass;

// Hibernate Search registers its built-in REST4 factory even when Quarkus selects the Vert.x
// factory explicitly. Remove this optional registration in native images so REST4 is unreachable.
@Delete
@TargetClass(className = "org.hibernate.search.backend.elasticsearch.client.impl.ElasticsearchClientBeanConfigurer")
final class Substitute_ElasticsearchClientBeanConfigurer {
}
