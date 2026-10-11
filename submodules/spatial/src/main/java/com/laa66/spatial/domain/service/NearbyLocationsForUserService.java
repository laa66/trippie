package com.laa66.spatial.domain.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyPointForUser;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.model.NearbyResultForUser;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.in.FindNearbyLocationsForUser;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * Composes the user-free geo lookup with the caller's unlock state. Framework-free by design —
 * wired to Spring in {@code infrastructure.config}.
 *
 * <p><strong>This class is the workaround for the frozen M6 cache, and it is also the right
 * split.</strong> It depends on {@link FindNearbyLocations} — the port, not the service — because
 * M6's Redis decorator will be wrapped around that bean, and everything inside that wrapper has to
 * stay a pure function of {@code (position, radius, categories)}: the frozen cache key has no user
 * dimension, so a per-user field anywhere in the cached payload would be stored under a key that
 * cannot tell users apart and handed to whoever asks next. Decorating out here means the cached unit
 * keeps exactly the shape M6 froze, and the identity never enters it.
 *
 * <p>Which is why the decoration also belongs <em>here</em> and not in the controller: the unlock
 * read is an outbound port, and {@code ArchitectureTest} forbids the web layer from touching one.
 */
public class NearbyLocationsForUserService implements FindNearbyLocationsForUser {

	private final FindNearbyLocations findNearbyLocations;
	private final UnlockRepository unlockRepository;

	public NearbyLocationsForUserService(
			FindNearbyLocations findNearbyLocations, UnlockRepository unlockRepository) {
		this.findNearbyLocations = findNearbyLocations;
		this.unlockRepository = unlockRepository;
	}

	@Override
	public NearbyResultForUser find(UUID userId, NearbyQuery query) {
		NearbyResult result = findNearbyLocations.find(query);

		// Scoped to the ids this page actually returned, so the second query is bounded by the
		// endpoint's limit rather than by how much the user has unlocked overall. The unlock table
		// is the only source of the flag: a row survives panning and category filtering, so a POI
		// leaving and re-entering the viewport comes back unlocked without any client-side cache.
		List<String> osmIds = result.items().stream().map(LocationPoint::osmId).toList();
		Set<String> unlocked = unlockRepository.findUnlocked(userId, osmIds);

		return new NearbyResultForUser(
				result.items().stream()
						.map(point -> new NearbyPointForUser(point, unlocked.contains(point.osmId())))
						.toList(),
				result.truncated());
	}
}
