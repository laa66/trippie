package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /reset-password} payload with boundary validation only. The password reuses the exact
 * registration rules — a char floor ({@code @Size(min = 8)}) plus the hard 72-<em>byte</em> BCrypt
 * ceiling ({@link Utf8MaxBytes}) — and the code is checked for shape (six digits), not existence, so
 * a malformed field is a 400 that leaks nothing. {@link NewPasswordDiffersFromCode} additionally
 * rejects a new password equal to the code. Neither the password nor the code is echoed on a 400.
 */
@NewPasswordDiffersFromCode
record ResetPasswordRequest(
		@NotBlank @Email String email,
		@NotBlank @Pattern(regexp = "\\d{6}") String code,
		@NotBlank @Size(min = 8) @Utf8MaxBytes(72) String newPassword) {
}
