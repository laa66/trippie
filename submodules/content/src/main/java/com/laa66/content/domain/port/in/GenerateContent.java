package com.laa66.content.domain.port.in;

import java.util.UUID;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerationStatus;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.model.UnsupportedContentModeException;

/**
 * Inbound port for asking that content be generated: the only call in the system that can lead to a
 * paid provider call. It answers with a status and never with content, which has one place to be
 * read from ({@link ReadPoiContent}).
 */
public interface GenerateContent {

	/**
	 * @return {@code PROCESSING} when a task is queued or another attempt is already generating,
	 *     {@code COMPLETED} when there is nothing to generate, and {@code FAILED} with the row's
	 *     failure class when its attempt budget is spent
	 * @throws UnsupportedContentModeException when {@code mode} is not served, before anything is asked
	 * @throws PoiNotUnlockedException when this user has not unlocked this POI
	 * @throws PoiUnlockStateUnavailableException when nobody can say whether they have
	 * @throws EventPublicationException when the broker did not confirm the task; the lease is given
	 *     back by then
	 */
	GenerationStatus generate(UUID userId, String osmId, ContentMode mode);
}
