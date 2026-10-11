package com.laa66.spatial.domain.service;

import java.util.Set;

import com.laa66.spatial.domain.model.InsufficientAccuracyException;
import com.laa66.spatial.domain.model.UnlockAttempt;
import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.model.UnlockResult;
import com.laa66.spatial.domain.model.UnlockSource;
import com.laa66.spatial.domain.port.in.UnlockLocations;
import com.laa66.spatial.domain.port.out.UnlockRepository;
import com.laa66.spatial.domain.port.out.UserPreferences;

/**
 * Application service owning the unlock policy: the radius, the accuracy threshold, and the one
 * read of the user's proximity preference. Framework-free by design — wired to Spring in
 * {@code infrastructure.config}.
 */
public class UnlockLocationsService implements UnlockLocations {

	/**
	 * The frozen unlock radius. Here rather than in the adapter so that the geometric policy sits
	 * next to the accuracy policy it is paired with, and so that the SQL has no number of its own to
	 * drift from. The client keeps its own copy of this constant (M3-07) for the same reason the
	 * category contract is duplicated: the client saves a pointless round trip, the server is the
	 * enforcement point that survives a client regression.
	 *
	 * <p><strong>The edge is exclusive, and the two sides are allowed to disagree on it.</strong>
	 * {@code ST_DWithin(..., 50)} was measured to refuse a POI at exactly 50.0 m and to accept one
	 * at 49.9999 m, so the server's rule is "strictly nearer than 50 m" and it stays that way; the
	 * client gates on {@code < 50} to match. Agreement at the edge is not achievable anyway: the
	 * client measures with haversine on a sphere and the server with {@code ST_Distance} on the
	 * WGS84 spheroid, which differ by roughly 15 cm at this range. A POI the client showed as
	 * in-range coming back {@code refused} within centimetres of the boundary is therefore a normal
	 * outcome of two distance formulas, not a failure and not something to retry around.
	 */
	static final int UNLOCK_RADIUS_METERS = 50;

	/** Worst fix accuracy a position claim may carry and still be measured against the radius. */
	static final int MAX_ACCURACY_METERS = 100;

	private final UnlockRepository unlockRepository;
	private final UserPreferences userPreferences;

	public UnlockLocationsService(UnlockRepository unlockRepository, UserPreferences userPreferences) {
		this.unlockRepository = unlockRepository;
		this.userPreferences = userPreferences;
	}

	@Override
	public UnlockResult unlock(UnlockAttempt attempt) {
		// Read once, here, at the moment a row is created, and persisted as granted_by so no later
		// reader has to ask again. The port is total and fails closed, so "proximity required" is
		// also what an auth outage yields: answering "not required" on a failed lookup would mint
		// permanent EXEMPT rows for a user who never asked for them, and there is no re-lock.
		boolean requireProximity = userPreferences.requireProximity(attempt.userId());

		// The accuracy gate belongs to proximity enforcement, so it is skipped for an exempt user:
		// the gate exists to stop a position that was never measured (a fallback) from standing in
		// for one that was, and an exempt grant does not consult the position at all. Keeping it
		// unconditional would also break the toggle as the manual-testing route, since a DevTools
		// geolocation override reports accuracy around 150 m.
		if (requireProximity && attempt.accuracyMeters() > MAX_ACCURACY_METERS) {
			throw new InsufficientAccuracyException(
					"accuracyMeters must be at most " + MAX_ACCURACY_METERS + " to unlock by proximity");
		}

		Set<String> unlocked = unlockRepository.grant(new UnlockGrant(
				attempt.userId(),
				attempt.lat(),
				attempt.lon(),
				UNLOCK_RADIUS_METERS,
				attempt.osmIds(),
				requireProximity ? UnlockSource.PROXIMITY : UnlockSource.EXEMPT));

		return UnlockResult.partition(attempt.osmIds(), unlocked);
	}
}
