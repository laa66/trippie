package com.laa66.auth.infrastructure.web;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects a list containing any slug outside the eight-slug POI category contract
 * ({@link com.laa66.auth.domain.model.CategorySlugs#VALID}). An empty list is valid (the DB CHECK
 * is a subset check, and an empty selection just means no POIs); {@code null} is left to
 * {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = CategorySlugsValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
@interface CategorySlugs {

	String message() default "must contain only known category slugs";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
