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
 * <p>{@code /forgot-password} returns ONLY an empty 200 or a shape-400 from bean validation, so it
 * never enumerates accounts: {@link com.laa66.auth.domain.service.ForgotPasswordService} does not
 * throw, an unknown email issues nothing, and a real account over the send-cap is a silent no-op
 * (the throttle is keyed on a hash of the normalized email for both branches, so its state cannot be
 * probed either). There is no 429 on this path. {@code /reset-password} returns an empty 200 on
 * success; every failure surfaces as a ProblemDetail via {@code AuthExceptionHandler} (bad OTP
 * → generic 400, malformed field → 400).
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
