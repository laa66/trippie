package com.laa66.content.infrastructure.web;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.in.RecordGenerationResult;

/**
 * The worker's surface: the pre-check before every provider attempt and the write-back of its
 * result. No {@code X-User-Id}: the worker is not a user, and these calls are not gated.
 *
 * <p><strong>Unreachable from a browser because the gateway denies it, not because of the
 * route.</strong> {@code /api/content/internal/**} is under the content route and would be forwarded
 * here as {@code /internal/**} with any user's token; the gateway's {@code SecurityConfig} denies that
 * prefix before routing (M4-02). Inside the compose network nothing authenticates the worker (LATER:
 * mTLS), the same trust spatial's {@code /unlocked} read places in the network.
 *
 * <p>The write-back answers {@code 204} whether or not the fence let it through: a refused write is
 * the expected answer to a redelivered or stale result, and the worker has nothing to do differently.
 */
@RestController
public class InternalGenerationController {

	private final RecordGenerationResult recordGenerationResult;

	public InternalGenerationController(RecordGenerationResult recordGenerationResult) {
		this.recordGenerationResult = recordGenerationResult;
	}

	@GetMapping("/internal/lease")
	public LeaseResponse lease(
			@RequestParam String poiId,
			@RequestParam ContentMode mode,
			@RequestParam String lang,
			@RequestParam Instant leasedAt) {

		return new LeaseResponse(recordGenerationResult.isLeaseLive(new PoiContentKey(poiId, mode, lang), leasedAt));
	}

	@PostMapping("/internal/results")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void result(@RequestBody ResultRequest request) {
		PoiContentKey key = request.key();
		if (key == null || key.osmId() == null || key.mode() == null || key.lang() == null
				|| request.leasedAt() == null || request.status() == null) {
			throw badRequest("key, leasedAt and status are required");
		}
		switch (request.status()) {
			case COMPLETED -> {
				if (request.promptVersion() == null || request.failedClass() != null || request.failedDetail() != null) {
					throw badRequest("a completion carries text and promptVersion and no failure");
				}
				recordGenerationResult.complete(key, request.leasedAt(), request.text(), request.promptVersion());
			}
			case FAILED -> {
				if (request.failedClass() == null || request.text() != null || request.promptVersion() != null) {
					throw badRequest("a failure carries failedClass and no text");
				}
				recordGenerationResult.fail(key, request.leasedAt(), request.failedClass(), request.failedDetail());
			}
			case PROCESSING -> throw badRequest("a result is COMPLETED or FAILED");
		}
	}

	private static ResponseStatusException badRequest(String reason) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
	}

	public record LeaseResponse(boolean live) {
	}

	/** The task's own {@code key} and {@code leasedAt}, as the worker received them, plus the outcome. */
	record ResultRequest(PoiContentKey key, Instant leasedAt, ContentStatus status, String text,
			String promptVersion, FailedClass failedClass, String failedDetail) {
	}
}
