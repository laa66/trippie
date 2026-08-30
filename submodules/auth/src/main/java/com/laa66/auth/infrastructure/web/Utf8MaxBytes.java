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
 * Rejects a string whose UTF-8 encoding exceeds {@link #value()} bytes. Bean-validation {@code @Size}
 * counts chars, but BCrypt truncates at 72 <em>bytes</em> — so a password of many multi-byte chars
 * could pass {@code @Size(max=72)} yet be silently shortened before hashing. This constraint pins the
 * real limit, so an over-length input becomes a 400 ProblemDetail instead of a weakened hash.
 */
@Documented
@Constraint(validatedBy = Utf8MaxBytesValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
@interface Utf8MaxBytes {

	int value();

	String message() default "must be at most {value} bytes when UTF-8 encoded";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
