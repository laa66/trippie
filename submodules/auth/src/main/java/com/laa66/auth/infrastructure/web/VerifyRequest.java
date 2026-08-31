package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /verify} payload with boundary validation only. The code shape (exactly 6 digits) is
 * a format check, not an existence check, so rejecting a malformed code with a 400 leaks nothing.
 */
record VerifyRequest(
		@NotBlank @Email String email,
		@NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
