package com.laa66.auth.domain.port.in;

import java.util.List;
import java.util.UUID;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.UserSettingsData;

/**
 * Inbound port for {@code PUT /settings} (flow 06). The user id always comes from the caller's
 * trusted identity (the gateway-set {@code X-User-Id} header), never from a request body or path.
 */
public interface UpdateUserSettings {

	UserSettingsData update(UUID userId, ContentMode mode, List<String> categories);
}
