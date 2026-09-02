package com.laa66.auth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct unit test of the cross-field rule, in isolation from the length/shape constraints that
 * would otherwise mask it (a six-digit code can never reach the eight-char password floor, so the IT
 * alone could not prove this rule). Removing or inverting the {@code !equals} in the validator turns
 * {@link #equalPasswordAndCode_isInvalid()} red.
 */
class NewPasswordDiffersFromCodeValidatorTest {

	private final NewPasswordDiffersFromCodeValidator validator = new NewPasswordDiffersFromCodeValidator();

	@Test
	void equalPasswordAndCode_isInvalid() {
		ResetPasswordRequest request = new ResetPasswordRequest("user@example.com", "123456", "123456");
		assertThat(validator.isValid(request, null))
				.as("a new password equal to the one-time code must be rejected").isFalse();
	}

	@Test
	void differentPasswordAndCode_isValid() {
		ResetPasswordRequest request = new ResetPasswordRequest("user@example.com", "123456", "Br4ndNewPass!");
		assertThat(validator.isValid(request, null)).isTrue();
	}

	@Test
	void nullFields_pass_soPerFieldConstraintsReportInstead() {
		assertThat(validator.isValid(new ResetPasswordRequest("user@example.com", null, "Br4ndNewPass!"), null))
				.as("a null code is left to @NotBlank, not this rule").isTrue();
		assertThat(validator.isValid(new ResetPasswordRequest("user@example.com", "123456", null), null))
				.as("a null password is left to @NotBlank, not this rule").isTrue();
		assertThat(validator.isValid(null, null)).isTrue();
	}
}
