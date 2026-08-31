package com.laa66.auth.domain.port.in;

/**
 * Inbound port for re-issuing a verification OTP (flow NEW). Subject to the send-throttle; throws
 * {@link com.laa66.auth.domain.model.OtpThrottledException} (429) past the cap. An unknown or
 * already-verified email is a silent no-op (no OTP, no throttle spend) so it does not become an
 * enumeration oracle.
 */
public interface ResendVerification {

	void resend(String email);
}
