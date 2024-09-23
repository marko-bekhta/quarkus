package io.quarkus.it.hibernate.validator.injection;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@ApplicationScoped
public class InjectedPropertyConstraintValidator
        implements ConstraintValidator<InjectedPropertyConstraintValidatorConstraint, String> {

    @Inject
    ValidationConfigProperties config;
    private Pattern pattern;

    @Override
    public void initialize(InjectedPropertyConstraintValidatorConstraint constraintAnnotation) {
        pattern = Pattern.compile(config.pattern());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        return pattern.matcher(value).matches();
    }
}
