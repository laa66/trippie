package com.laa66.auth.infrastructure.web;

import java.util.List;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Null passes (leave presence to {@code @NotNull}); every element must be one of the 8 known slugs. */
class CategorySlugsValidator implements ConstraintValidator<CategorySlugs, List<String>> {

	@Override
	public boolean isValid(List<String> value, ConstraintValidatorContext context) {
		return value == null
				|| value.stream().allMatch(com.laa66.auth.domain.model.CategorySlugs.VALID::contains);
	}
}
