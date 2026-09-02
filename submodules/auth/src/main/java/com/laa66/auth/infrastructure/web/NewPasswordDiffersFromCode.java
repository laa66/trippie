package com.laa66.auth.infrastructure.web;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Class-level constraint rejecting a reset request whose new password equals the submitted OTP. A
 * pure comparison of two request fields (no account lookup), so it leaks nothing and runs at the web
 * boundary before the OTP is ever checked. Belt-and-suspenders: {@code @Size(min = 8)} on the
 * password and the six-digit code shape already make them unequal, but this pins the rule explicitly
 * so it survives any future change to those bounds.
 */
@Documented
@Constraint(validatedBy = NewPasswordDiffersFromCodeValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
@interface NewPasswordDiffersFromCode {

	String message() default "new password must not equal the one-time code";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
