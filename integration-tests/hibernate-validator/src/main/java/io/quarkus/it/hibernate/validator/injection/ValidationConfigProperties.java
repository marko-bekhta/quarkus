package io.quarkus.it.hibernate.validator.injection;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "some.app.config.validation")
public interface ValidationConfigProperties {
    String pattern();
}
