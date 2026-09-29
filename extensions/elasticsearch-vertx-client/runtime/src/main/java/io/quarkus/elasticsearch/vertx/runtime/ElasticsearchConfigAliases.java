package io.quarkus.elasticsearch.vertx.runtime;

import java.util.Map;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigSourceInterceptorFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

/** Resolves legacy property names using config source priority before applying defaults. */
@StaticInitSafe
public class ElasticsearchConfigAliases implements ConfigSourceInterceptorFactory {
    private static final Map<String, String> ALIASES = Map.of(
            "quarkus.elasticsearch.connect-timeout", "quarkus.elasticsearch.connection-timeout",
            "quarkus.elasticsearch.read-idle-timeout", "quarkus.elasticsearch.socket-timeout",
            "quarkus.elasticsearch.http1-max-pool-size", "quarkus.elasticsearch.max-connections-per-route");

    @Override
    public ConfigSourceInterceptor getInterceptor(ConfigSourceInterceptorContext context) {
        return new ConfigSourceInterceptor() {
            @Override
            public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
                ConfigValue canonical = context.proceed(name);
                String alias = ALIASES.get(name);
                if (alias == null) {
                    return canonical;
                }
                ConfigValue legacy = context.proceed(alias);
                if (legacy != null && legacy.getValue() != null
                        && (canonical == null || canonical.getValue() == null
                                || ConfigValue.CONFIG_SOURCE_COMPARATOR.compare(legacy, canonical) > 0)) {
                    return legacy.withName(name);
                }
                return canonical;
            }
        };
    }

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(Priorities.LIBRARY + 300);
    }
}
