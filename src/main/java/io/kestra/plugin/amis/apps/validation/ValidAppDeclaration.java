package io.kestra.plugin.amis.apps.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Constraint(validatedBy = AppDeclarationValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAppDeclaration {
    String message() default "Invalid AMIS declaration";
    Class<?>[] groups() default { };
    Class<? extends Payload>[] payload() default { };
}
