package com.laa66.auth.infrastructure.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.port.in.ResendVerification;
import com.laa66.auth.domain.port.in.VerifyEmail;

import jakarta.validation.Valid;

/**
 * Public email-verification endpoints (flow NEW). The gateway strips {@code /api/auth}, so these
 * map {@code /verify} and {@code /resend}. Both are public (the user is not yet logged in), so the
 * account is identified by the email in the body — never a trusted {@code X-User-Id}, which the
 * gateway does not set on public routes. Both return an empty 200 on the happy path so nothing about
 * account state is echoed; failures surface as ProblemDetail via {@code AuthExceptionHandler}.
 */
@RestController
public class VerificationController {

	private final VerifyEmail verifyEmail;
	private final ResendVerification resendVerification;

	public VerificationController(VerifyEmail verifyEmail, ResendVerification resendVerification) {
		this.verifyEmail = verifyEmail;
		this.resendVerification = resendVerification;
	}

	@PostMapping("/verify")
	public ResponseEntity<Void> verify(@Valid @RequestBody VerifyRequest request) {
		verifyEmail.verify(request.email(), request.code());
		return ResponseEntity.ok().build();
	}

	@PostMapping("/resend")
	public ResponseEntity<Void> resend(@Valid @RequestBody ResendRequest request) {
		resendVerification.resend(request.email());
		return ResponseEntity.ok().build();
	}
}
