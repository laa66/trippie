package com.laa66.spatial.infrastructure.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.domain.port.in.CheckLocationUnlocked;

/**
 * Inbound HTTP adapter for the single-POI unlock read. Drives {@link CheckLocationUnlocked} only —
 * never {@code UnlockRepository}, which is what keeps this read in the same package as the write
 * without being a route to it.
 *
 * <p>{@code X-User-Id} is <strong>required</strong> and bound as a {@link UUID}, the convention
 * M3-04 set on {@code /locations/unlock} and M3-05 on {@code /locations/nearby}: an absent or
 * malformed header is a 400 from Spring's own handling, not an anonymous read answering
 * {@code false}. The answer is a claim about a specific user, so with nobody identified there is no
 * honest value to serve, and a gateway that stops forwarding the header must fail loudly instead of
 * locking every POI for everyone. The {@code UUID} parse keeps a malformed subject from reaching the
 * query at all: this endpoint makes no outbound call of its own, so what it buys here is a 400
 * instead of a lookup against whatever string arrived.
 *
 * <p><strong>An unknown POI is {@code {"unlocked": false}} with a 200, deliberately not a 404.</strong>
 * "Not unlocked" is true of a POI that does not exist, the caller (content, M4-04) does the same
 * thing with either answer, and a 404 here would turn this endpoint into an existence oracle for
 * callers who have no business asking. Existence is the detail endpoint's question, and it answers
 * it with a 404 there.
 *
 * <p>The id is two path segments for the reason {@link LocationDetailController} documents.
 */
@RestController
public class LocationUnlockStateController {

	private final CheckLocationUnlocked checkLocationUnlocked;

	public LocationUnlockStateController(CheckLocationUnlocked checkLocationUnlocked) {
		this.checkLocationUnlocked = checkLocationUnlocked;
	}

	@GetMapping("/locations/{osmType}/{osmRef}/unlocked")
	public UnlockStateResponse unlocked(
			@RequestHeader("X-User-Id") UUID userId,
			@PathVariable String osmType,
			@PathVariable String osmRef) {

		return new UnlockStateResponse(checkLocationUnlocked.isUnlocked(userId, osmType + "/" + osmRef));
	}
}
