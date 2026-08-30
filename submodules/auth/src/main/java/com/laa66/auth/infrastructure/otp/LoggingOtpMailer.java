package com.laa66.auth.infrastructure.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;

/**
 * Dev-stub {@link OtpMailer}: logs the code instead of sending an email. Real transactional
 * delivery is deferred to the LATER Notification service; swapping this for a real adapter is the
 * only change needed. Only the OTP is logged here — never any password.
 */
@Component
class LoggingOtpMailer implements OtpMailer {

	private static final Logger log = LoggerFactory.getLogger(LoggingOtpMailer.class);

	@Override
	public void send(OtpPurpose purpose, String recipient, String code) {
		log.info("[dev-stub OtpMailer] {} OTP for {}: {}", purpose, recipient, code);
	}
}
