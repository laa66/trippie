package com.laa66.spatial.domain.port.out;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.laa66.spatial.domain.model.UnlockGrant;

/**
 * Outbound port for unlock state — spatial's first write path. As with {@code LocationRepository},
 * the adapter behind it is the only place SQL lives.
 *
 * <p>Both consumers are served by the {@code (user_id, osm_id)} primary key: the write conflicts on
 * it, the read filters on its leading column.
 */
public interface UnlockRepository {

	/**
	 * Grants every requested POI that the grant's position actually reaches (or every one that
	 * exists, when the grant is {@code EXEMPT}), idempotently, and returns the requested ids that
	 * are unlocked for that user afterwards.
	 *
	 * <p><strong>The distance predicate is part of this statement, not of the caller.</strong>
	 * Unlocking is defined as a geometric predicate, so the only implementation that can be trusted
	 * is one where the gate and the insert are the same statement: a two-step "check, then write"
	 * would be a write that any future refactor could reach without the check. {@code radiusMeters}
	 * and the {@code EXEMPT} decision come from the application service, which owns the policy.
	 *
	 * <p><strong>Which is also what a caller is promising here.</strong> This method takes the radius
	 * and the source as arguments, so it will grant whatever it is told to grant: a 50 km radius, or
	 * {@code PROXIMITY} for a position nobody measured, yields rows indistinguishable from honest
	 * ones, with no re-lock to take them back. {@code UnlockLocationsService} is the only legitimate
	 * caller, and a second one is a decision to be reviewed, not a detail.
	 *
	 * <p><strong>Existence is enforced by a join against {@code location_point}, not by the foreign
	 * key.</strong> The key rejects the statement rather than the row, so a batch carrying one
	 * unknown {@code osm_id} would persist nothing at all; selecting the grant's rows out of
	 * {@code location_point} instead makes an unknown id simply absent from the result, which is
	 * what lets it be reported as {@code refused}. The FK stays as the schema-level backstop. The
	 * same join de-duplicates: {@code location_point.osm_id} is a primary key, so a repeated id in
	 * the request yields one row no matter how often it was asked for.
	 *
	 * <p><strong>The result is state, not a delta.</strong> An id already unlocked before this call
	 * is in the returned set, because the HTTP contract puts an already-unlocked POI in
	 * {@code unlocked} and reserves {@code refused} for "too far away, or no such POI". That
	 * includes an already-unlocked POI the caller is now far away from: the row is a fact that was
	 * established once and there is no re-lock, so distance cannot retract it.
	 *
	 * <p>Nothing is overwritten: the first grant is the fact, keeping its original
	 * {@code unlocked_at} and {@code granted_by}. Re-granting from a different source must not
	 * rewrite why or when a POI was unlocked, because that is the only record of how the row came to
	 * exist.
	 *
	 * <p><strong>Concurrency.</strong> Two requests for the same {@code (userId, osmId)} — a retry
	 * after a client timeout, two tabs, two devices — both report the POI as unlocked; the second one
	 * waits for the first and reads its committed row. The one case the implementation cannot close
	 * is a concurrent grant for a POI the asserted position does not itself reach, which may come
	 * back {@code refused} until the next call. See {@code PostgisUnlockAdapter}'s {@code GRANT_SQL}
	 * for the mechanics.
	 *
	 * <p>An empty {@code osmIds} is not a shape to optimise for — the HTTP boundary rejects it with
	 * 400, so reaching here with nothing to grant is a bug upstream.
	 */
	Set<String> grant(UnlockGrant grant);

	/**
	 * Returns the subset of {@code osmIds} already unlocked for {@code userId}. Scoped to one user
	 * by construction — there is no signature here that can return another user's rows.
	 */
	Set<String> findUnlocked(UUID userId, Collection<String> osmIds);
}
