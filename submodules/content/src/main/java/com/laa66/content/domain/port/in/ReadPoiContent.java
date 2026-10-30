package com.laa66.content.domain.port.in;

import java.util.Optional;
import java.util.UUID;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.model.UnsupportedContentModeException;

/**
 * Inbound port for the gated content read: the status poll and the delivery of the text in one call.
 */
public interface ReadPoiContent {

	/**
	 * @return the row for this POI, or empty when nothing has been generated yet
	 * @throws UnsupportedContentModeException when {@code mode} is not served, before anything is asked
	 * @throws PoiNotUnlockedException when this user has not unlocked this POI
	 * @throws PoiUnlockStateUnavailableException when nobody can say whether they have
	 */
	Optional<PoiContent> read(UUID userId, String osmId, ContentMode mode);
}
