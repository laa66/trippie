package com.laa66.spatial.infrastructure.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.domain.model.UnlockAttempt;
import com.laa66.spatial.domain.port.in.UnlockLocations;

/**
 * Inbound HTTP adapter for the unlock use case. Drives {@link UnlockLocations} only — never the
 * repository — and owns all boundary validation of the raw body, since {@link UnlockAttempt} is
 * trusted from there on. The path carries no {@code /api/spatial} prefix: the gateway strips it.
 *
 * <p>No policy lives here. The radius, the accuracy threshold and the proximity-preference read all
 * belong to the application service, so this class cannot grant anything by itself and a 422 is
 * raised where the write would otherwise happen rather than one layer above it.
 */
@RestController
public class UnlockLocationsController {

	/**
	 * Cap on one batch. A batch exists to tighten the grant — one position claim, one radius — so
	 * the cap only has to be larger than the densest cluster a 50 m radius can hold, while keeping
	 * the bound array, and the statement planned over it, a fixed small size.
	 */
	static final int MAX_OSM_IDS = 50;

	private final UnlockLocations unlockLocations;

	public UnlockLocationsController(UnlockLocations unlockLocations) {
		this.unlockLocations = unlockLocations;
	}

	/**
	 * {@code X-User-Id} is bound as a {@link UUID}, not a {@code String}. That parse is the only
	 * thing standing between this header and the one the auth adapter builds from
	 * {@code userId.toString()}: a {@code String} subject would let a crafted header value carry a
	 * newline and inject a second header into spatial's own outbound call to auth. An absent or
	 * malformed header is a 400 from Spring's own handling, not a silent anonymous unlock.
	 */
	@PostMapping("/locations/unlock")
	public UnlockResponse unlock(@RequestHeader("X-User-Id") UUID userId, @RequestBody UnlockRequest request) {
		return UnlockResponse.from(unlockLocations.unlock(toAttempt(userId, request)));
	}

	private UnlockAttempt toAttempt(UUID userId, UnlockRequest request) {
		// Negated-range form rather than `lat < -90 || lat > 90`, and it is load-bearing: a NaN
		// compares false against every bound, so the readable form would wave it through. Jackson
		// coerces the JSON string "NaN" (and 1e400, and "Infinity") into a Double, so this is
		// reachable from the wire, not theoretical.
		//
		// What it buys is the right status code, not safety. ST_DWithin against a NaN point was
		// measured to return false rather than error or match, so a NaN would fail closed on its own
		// — every id refused — and the exempt path never consults the position at all. So the choice
		// here is 400 over a silently empty 200, which is the difference between a client bug that
		// is visible and one that reads as "nothing nearby".
		if (request.lat() == null || !(request.lat() >= -90 && request.lat() <= 90)) {
			throw new InvalidRequestException("lat is required and must be in [-90, 90]");
		}
		if (request.lon() == null || !(request.lon() >= -180 && request.lon() <= 180)) {
			throw new InvalidRequestException("lon is required and must be in [-180, 180]");
		}
		// Required, finite and positive, unconditionally and at the boundary: this is the contract
		// element that makes the server able to tell a measured position from a fallback, which has
		// no accuracy to report. The 100 m threshold is policy and is applied in the service, which
		// only enforces it when the user still requires proximity.
		//
		// Finiteness is checked here and not left to that threshold, because the threshold is the
		// half of the contract that an exempt user is excused from: an Infinity (1e400, or the
		// string "Infinity") is > 0 and > 100, so the service rejects it while proximity is
		// required and waves it through the moment it is not — granting from a position whose
		// "measured" accuracy is unbounded, which is precisely the unmeasured position this field
		// exists to exclude.
		if (request.accuracyMeters() == null
				|| !(Double.isFinite(request.accuracyMeters()) && request.accuracyMeters() > 0)) {
			throw new InvalidRequestException("accuracyMeters is required and must be a positive finite number");
		}

		List<String> osmIds = request.osmIds();
		if (osmIds == null || osmIds.isEmpty()) {
			throw new InvalidRequestException("osmIds is required and must not be empty");
		}
		if (osmIds.size() > MAX_OSM_IDS) {
			throw new InvalidRequestException("osmIds must hold at most " + MAX_OSM_IDS + " ids");
		}
		// NUL alongside null and blank: it is the one character a Postgres `text` column cannot
		// store, so an id carrying it is rejected at the bind and surfaces as a 500 for what is
		// plainly a malformed request — the same wrong status a null id used to produce. Nothing
		// else needs screening here: a JSON string that Jackson has parsed is valid UTF-16, and
		// every other code point, newlines and control characters included, round-trips through
		// `text` untouched (and reaches the DB as a bound parameter, never as SQL).
		if (osmIds.stream().anyMatch(id -> id == null || id.isBlank() || id.indexOf('\0') >= 0)) {
			throw new InvalidRequestException("osmIds must not contain a null, blank or NUL-bearing id");
		}

		// De-duplicated here because the body is a List: nothing downstream has to cope with the
		// same id twice, and a repeated id cannot appear in both unlocked and refused.
		return new UnlockAttempt(
				userId, request.lat(), request.lon(), request.accuracyMeters(), new LinkedHashSet<>(osmIds));
	}
}
