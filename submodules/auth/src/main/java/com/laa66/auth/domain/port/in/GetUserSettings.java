package com.laa66.auth.domain.port.in;

import java.util.UUID;

import com.laa66.auth.domain.model.UserSettingsData;

/**
 * Inbound port for {@code GET /settings} (flow 06). The user id always comes from the caller's
 * trusted identity (the gateway-set {@code X-User-Id} header), never from a request body or path.
 */
public interface GetUserSettings {

	UserSettingsData get(UUID userId);
}
