package com.laa66.worker.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One generation order as content publishes it. This is the worker's own copy of content's
 * {@code GenerateContentTask}: the two modules share no library, the JSON field names are the
 * contract (content's publisher writes no type header for that reason), and
 * {@code ContentContractTest} fails when the two records drift apart.
 *
 * <p>{@code leasedAt} is the lease token. Content's write-backs compare it for equality against a
 * microsecond {@code timestamptz}, so it is carried as the parsed {@link Instant} and never
 * formatted, truncated or converted on the way through: a token rounded to milliseconds misses the
 * fence silently (content M4-03a).
 */
public record GenerateContentTask(PoiContentKey key, Instant leasedAt) {

	public GenerateContentTask {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(key.osmId(), "key.osmId");
		Objects.requireNonNull(key.mode(), "key.mode");
		Objects.requireNonNull(key.lang(), "key.lang");
		Objects.requireNonNull(leasedAt, "leasedAt");
	}
}
