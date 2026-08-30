package com.laa66.auth.domain.port.out;

import com.laa66.auth.domain.model.OtpPurpose;

/**
 * Outbound port for delivering a one-time code to the user. The MVP adapter is a dev stub that logs
 * the code; real transactional delivery is deferred to the LATER Notification service.
 */
public interface OtpMailer {

	void send(OtpPurpose purpose, String recipient, String code);
}
