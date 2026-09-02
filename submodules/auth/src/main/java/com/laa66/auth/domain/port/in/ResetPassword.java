package com.laa66.auth.domain.port.in;

/**
 * Inbound port for completing a password reset (flow NEW). Resolves the account by email, checks the
 * submitted RESET OTP under the same attempt-cap/invalidate semantics as verification, sets a fresh
 * BCrypt hash, and revokes every refresh-token family the user owns (force re-login everywhere).
 * Throws {@link com.laa66.auth.domain.model.InvalidOtpException} on any non-success (wrong, expired,
 * absent code, attempt cap, or an email that resolves to no account) so the boundary maps a single
 * generic 400 with no enumeration oracle.
 */
public interface ResetPassword {

	void reset(String email, String code, String newPassword);
}
