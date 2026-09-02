package com.laa66.auth.infrastructure.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Passes when the new password differs from the code. A null on either side passes here — presence
 * and shape are left to the per-field {@code @NotBlank}/{@code @Size}/{@code @Pattern} constraints,
 * so this never double-reports.
 */
class NewPasswordDiffersFromCodeValidator
		implements ConstraintValidator<NewPasswordDiffersFromCode, ResetPasswordRequest> {

	@Override
	public boolean isValid(ResetPasswordRequest request, ConstraintValidatorContext context) {
		if (request == null || request.code() == null || request.newPassword() == null) {
			return true;
		}
		return !request.code().equals(request.newPassword());
	}
}
