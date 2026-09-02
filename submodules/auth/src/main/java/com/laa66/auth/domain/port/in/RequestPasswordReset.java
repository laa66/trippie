package com.laa66.auth.domain.port.in;

/**
 * Inbound port for the forgot-password use case (flow NEW). Resolves the account by email and, for a
 * real account within the send-cap, issues a RESET OTP through the same hashed store/mailer machinery
 * as verification. The endpoint ALWAYS succeeds from the caller's view: an unknown email and a
 * rate-limited real account are both silent no-ops, so it returns 200 in every case and leaks nothing
 * about account existence or throttle state — neither by status nor by timing (the issuance runs off
 * the request thread). It therefore never throws for the happy or the throttled path.
 */
public interface RequestPasswordReset {

	void requestReset(String email);
}
