package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /login} payload. Only boundary shape is validated (non-blank, email-shaped) — never a
 * length/strength policy, which would leak nothing but also serves no purpose on login. A malformed
 * email is a 400 (bad input, same for everyone); a well-formed unknown email falls through to the
 * uniform 401.
 */
record LoginRequest(
		@NotBlank @Email String email,
		@NotBlank String password) {
}
