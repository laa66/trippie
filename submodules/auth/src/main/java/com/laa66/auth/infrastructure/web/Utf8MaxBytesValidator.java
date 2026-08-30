package com.laa66.auth.infrastructure.web;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Null passes (leave presence to {@code @NotBlank}); otherwise the UTF-8 byte length is bounded. */
class Utf8MaxBytesValidator implements ConstraintValidator<Utf8MaxBytes, String> {

	private int max;

	@Override
	public void initialize(Utf8MaxBytes constraint) {
		this.max = constraint.value();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
	}
}
