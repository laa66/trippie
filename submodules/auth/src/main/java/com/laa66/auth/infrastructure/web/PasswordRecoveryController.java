package com.laa66.auth.infrastructure.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.port.in.RequestPasswordReset;
import com.laa66.auth.domain.port.in.ResetPassword;

import jakarta.validation.Valid;

/**
 * Public password-recovery endpoints (flow NEW). The gateway strips {@code /api/auth}, so these map
 * {@code /forgot-password} and {@code /reset-password}. Both are public (the user cannot log in), so
 * the account is identified by the email in the body, never a trusted {@code X-User-Id}.
 *
 * <p>{@code /forgot-password} always returns an empty 200 for the account-existence dimension so it
 * never enumerates accounts (an unknown email is a silent no-op; a real account over the send-cap
 * still surfaces the shared 429). {@code /reset-password} returns an empty 200 on success; every
 * failure surfaces as a ProblemDetail via {@code AuthExceptionHandler} (bad OTP → generic 400,
 * malformed field → 400).
 */
@RestController
public class PasswordRecoveryController {

	private final RequestPasswordReset requestPasswordReset;
	private final ResetPassword resetPassword;

	public PasswordRecoveryController(RequestPasswordReset requestPasswordReset, ResetPassword resetPassword) {
		this.requestPasswordReset = requestPasswordReset;
		this.resetPassword = resetPassword;
	}

	@PostMapping("/forgot-password")
	public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		requestPasswordReset.requestReset(request.email());
		return ResponseEntity.ok().build();
	}

	@PostMapping("/reset-password")
	public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		resetPassword.reset(request.email(), request.code(), request.newPassword());
		return ResponseEntity.ok().build();
	}
}
