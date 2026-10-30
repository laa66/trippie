package com.laa66.content.domain.service;

import java.util.Optional;
import java.util.UUID;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.UnsupportedContentModeException;
import com.laa66.content.domain.port.in.ReadPoiContent;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * The gated read. Content is shared between users, so the gate is a step in front of the query and
 * not a condition inside it, and the order of the three steps is the contract: a mode that is not
 * served costs no call to spatial, and a user who has not unlocked the POI never causes a read, so
 * no response can depend on whether the content exists.
 *
 * <p>Framework-free by design; wired in {@code infrastructure.config}.
 */
public class ReadPoiContentService implements ReadPoiContent {

	private final PoiUnlocks poiUnlocks;
	private final ContentRepository contentRepository;

	public ReadPoiContentService(PoiUnlocks poiUnlocks, ContentRepository contentRepository) {
		this.poiUnlocks = poiUnlocks;
		this.contentRepository = contentRepository;
	}

	@Override
	public Optional<PoiContent> read(UUID userId, String osmId, ContentMode mode) {
		if (mode != ContentMode.TEXT) {
			throw new UnsupportedContentModeException();
		}
		if (!poiUnlocks.isUnlocked(userId, osmId)) {
			throw new PoiNotUnlockedException();
		}
		return contentRepository.find(new PoiContentKey(osmId, mode, PoiContentKey.SERVED_LANG));
	}
}
