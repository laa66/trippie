package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Register payload with boundary validation only. The minimum is a char floor; the maximum is a hard
 * 72-<em>byte</em> ceiling ({@link Utf8MaxBytes}) because BCrypt silently truncates the input past
 * 72 UTF-8 bytes — so an over-length password is rejected with a 400 rather than weakened. The
 * rejected password value is never echoed back in the 400 body.
 */
record RegisterRequest(
		@NotBlank @Email String email,
		@NotBlank @Size(min = 8) @Utf8MaxBytes(72) String password) {
}
