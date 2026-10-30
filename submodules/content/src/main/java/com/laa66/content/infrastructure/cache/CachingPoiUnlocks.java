package com.laa66.content.infrastructure.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * Remembers positive answers of {@link PoiUnlocks} in this process, so that polling a gated read
 * does not become one spatial call per poll per user.
 *
 * <p><strong>Only {@code true} is ever stored.</strong> A user does not lose an unlock, but deleting
 * a POI cascades to its unlocks ({@code V2__poi_unlock.sql} in spatial), so a positive can go stale,
 * and the TTL is what bounds how long it outlives the POI. The loader only upserts today, which makes
 * that path a safeguard. A negative is mutable in the other direction: the user may be walking to
 * the POI right now, and a remembered one would tell them to walk closer while they stand there. An
 * outage is not an answer either, so it passes through and leaves nothing behind. A held positive
 * also answers through an outage: the port is not asked at all.
 *
 * <p>The TTL runs from the write and a hit does not extend it. An expired entry answers nothing,
 * so past the TTL an outage surfaces as usual. The lock covers the map only, never the port call:
 * one slow spatial answer must not stall every other user. Two threads on a cold key may therefore
 * both ask, and a positive stored twice is the same positive.
 */
public class CachingPoiUnlocks implements PoiUnlocks {

	/** Constants and not properties, for the reason given on {@code ContentConfig.SPATIAL_TIMEOUT}. */
	public static final Duration TTL = Duration.ofMinutes(10);

	/** Least recently used goes first. Holds a key and an instant each, so a few MB at most. */
	public static final int MAX_ENTRIES = 10_000;

	private final PoiUnlocks delegate;
	private final InstantSource clock;
	private final Map<Key, Instant> expiries = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Instant> eldest) {
			return size() > MAX_ENTRIES;
		}
	};

	public CachingPoiUnlocks(PoiUnlocks delegate, InstantSource clock) {
		this.delegate = delegate;
		this.clock = clock;
	}

	@Override
	public boolean isUnlocked(UUID userId, String osmId) {
		Key key = new Key(userId, osmId);
		if (holds(key)) {
			return true;
		}
		boolean unlocked = delegate.isUnlocked(userId, osmId);
		if (unlocked) {
			remember(key);
		}
		return unlocked;
	}

	private synchronized boolean holds(Key key) {
		Instant expiry = expiries.get(key);
		if (expiry == null) {
			return false;
		}
		if (clock.instant().isBefore(expiry)) {
			return true;
		}
		expiries.remove(key);
		return false;
	}

	private synchronized void remember(Key key) {
		expiries.put(key, clock.instant().plus(TTL));
	}

	private record Key(UUID userId, String osmId) {
	}
}
