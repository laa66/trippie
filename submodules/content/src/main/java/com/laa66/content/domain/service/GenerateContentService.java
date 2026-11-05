package com.laa66.content.domain.service;

import java.time.Instant;
import java.util.UUID;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.UnsupportedContentModeException;
import com.laa66.content.domain.port.in.GenerateContent;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * Where money is committed. The order is the contract: mode, then the unlock gate, then the lease,
 * then the publish. A mode that is not served costs no call to spatial, a user who has not unlocked
 * the POI never touches the table, and nothing is published without a lease, so two clicks, two
 * users or two tabs produce one task.
 *
 * <p><strong>No transaction, on purpose.</strong> The lease is one autocommitted statement and the
 * publish runs after it has committed. A publish can take the whole confirm wait against a sick
 * broker, and inside a transaction that would hold a pooled connection, and the row lock, for as
 * long.
 *
 * <p><strong>A publish that fails gives the lease back through
 * {@link ContentRepository#releaseUnpublishedLease}</strong>, rather than leaving the row to expire.
 * The failure does not prove the task is absent (under a memory alarm it arrives later), so the
 * lease has to end now: that kills the token the late task carries. The release also refunds the
 * attempt, because no provider was called, and a broker outage must not park a POI for a day. If the
 * release itself fails, the row is left {@code PROCESSING} under a lease that expires after
 * {@link ContentRepository#GENERATION_LEASE_TIMEOUT} and is then taken over like any abandoned one,
 * at the cost of one attempt; the caller still gets the publication failure.
 *
 * <p>Framework-free by design; wired in {@code infrastructure.config}.
 */
public class GenerateContentService implements GenerateContent {

	private final PoiUnlocks poiUnlocks;
	private final ContentRepository contentRepository;
	private final EventPublisher eventPublisher;

	public GenerateContentService(PoiUnlocks poiUnlocks, ContentRepository contentRepository,
			EventPublisher eventPublisher) {
		this.poiUnlocks = poiUnlocks;
		this.contentRepository = contentRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	public ContentStatus generate(UUID userId, String osmId, ContentMode mode) {
		if (mode != ContentMode.TEXT) {
			throw new UnsupportedContentModeException();
		}
		if (!poiUnlocks.isUnlocked(userId, osmId)) {
			throw new PoiNotUnlockedException();
		}

		PoiContentKey key = new PoiContentKey(osmId, mode, PoiContentKey.SERVED_LANG);
		GenerationLease lease = contentRepository.leaseGeneration(key);
		return switch (lease.outcome()) {
			case GRANTED, RETRY_GRANTED -> {
				publish(key, lease.leasedAt());
				yield ContentStatus.PROCESSING;
			}
			case HELD_BY_ANOTHER_ATTEMPT -> ContentStatus.PROCESSING;
			case ALREADY_COMPLETED -> ContentStatus.COMPLETED;
			case ATTEMPTS_EXHAUSTED -> ContentStatus.FAILED;
		};
	}

	private void publish(PoiContentKey key, Instant leasedAt) {
		try {
			eventPublisher.publish(new GenerateContentTask(key, leasedAt));
		}
		catch (EventPublicationException publicationFailure) {
			try {
				contentRepository.releaseUnpublishedLease(key, leasedAt);
			}
			catch (RuntimeException releaseFailure) {
				publicationFailure.addSuppressed(releaseFailure);
			}
			throw publicationFailure;
		}
	}
}
