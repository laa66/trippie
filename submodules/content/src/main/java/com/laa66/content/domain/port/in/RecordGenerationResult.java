package com.laa66.content.domain.port.in;

import java.time.Instant;

import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.InvalidGeneratedTextException;
import com.laa66.content.domain.model.PoiContentKey;

/**
 * Inbound port for the worker: whether its lease is still worth spending on, and the result of the
 * work it did under it. User-free: the unlock gate was passed before the task existed, and a worker
 * that had to name a user to get here would be bypassing that gate, not using it.
 *
 * <p>Every call carries the lease token exactly as the task delivered it (see
 * {@code GenerationLease}); the fences behind these calls are the repository's.
 */
public interface RecordGenerationResult {

	/** See {@code ContentRepository#isLeaseLive}. */
	boolean isLeaseLive(PoiContentKey key, Instant leasedAt);

	/**
	 * Idempotent; see {@code ContentRepository#completeGeneration}.
	 *
	 * @throws InvalidGeneratedTextException if the text cannot be stored
	 */
	void complete(PoiContentKey key, Instant leasedAt, String text, String promptVersion);

	/** Fenced strictly by the token; see {@code ContentRepository#failGeneration}. */
	void fail(PoiContentKey key, Instant leasedAt, FailedClass failedClass, String failedDetail);
}
