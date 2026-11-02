package com.laa66.content.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The order to generate one piece of content: which key, and the lease it was issued under.
 *
 * <p>Both fields are exactly what {@code ContentRepository}'s write-backs take, so whoever executes
 * the task hands {@code key} and {@code leasedAt} back unchanged. {@code leasedAt} is
 * {@link GenerationLease#leasedAt()}: the fence compares it for equality, so every hop has to keep it
 * bit for bit (see {@link GenerationLease}). There is no user here on purpose: content is shared per
 * key, and the unlock gate has already been passed by the time a task exists.
 *
 * <p>The key's fields are checked here and not on {@link PoiContentKey}: a task crosses a process
 * boundary and a hole in it surfaces only in another service, while the key's rules stay with the
 * table's CHECKs for the reason it states.
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
