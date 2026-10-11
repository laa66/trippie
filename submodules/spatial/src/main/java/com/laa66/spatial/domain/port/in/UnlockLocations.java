package com.laa66.spatial.domain.port.in;

import com.laa66.spatial.domain.model.InsufficientAccuracyException;
import com.laa66.spatial.domain.model.UnlockAttempt;
import com.laa66.spatial.domain.model.UnlockResult;

/**
 * Inbound port: spatial's one write use case. Keeps the controller off both outbound ports, so the
 * proximity policy — the radius, the accuracy threshold and the preference read — has exactly one
 * home.
 *
 * <p><strong>Two barriers hold that, and it is worth knowing exactly how far each reaches.</strong>
 * {@code UnlockRepository} is public because this service depends on it, so visibility alone does
 * not stop a bean from injecting it and calling {@code grant} with a radius of its own.
 * <ul>
 * <li>The adapter behind the port is package-private, so the SQL cannot be reached by naming,
 * constructing or subclassing it.
 * <li>{@code ArchitectureTest#theHttpBoundaryCannotReachAnOutboundPort} fails the build if anything
 * in {@code infrastructure.web} so much as declares a dependency on {@code domain.port.out}. That
 * covers the realistic bypass — a controller with a request in hand, skipping the policy on the way
 * past — and it covers the ports M3-05 and M4 add, because it names the package and not this type.
 * </ul>
 *
 * <p><strong>What is still not enforced:</strong> a new class inside {@code domain} — another
 * application service, say — may inject {@code UnlockRepository} and grant from 50 km away, and no
 * rule here will object. An allowlist of permitted callers was considered and rejected
 * ({@code ArchitectureTest} records why). So the guarantee is "the HTTP layer cannot bypass this
 * policy", not "nothing can"; the rest is {@code UnlockRepository#grant}'s contract and review.
 */
public interface UnlockLocations {

	/**
	 * @throws InsufficientAccuracyException when proximity is required and the asserted fix is too
	 *     coarse to be measured against; nothing is persisted in that case.
	 */
	UnlockResult unlock(UnlockAttempt attempt);
}
