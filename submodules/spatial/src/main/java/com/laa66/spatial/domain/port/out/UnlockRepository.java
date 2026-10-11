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
	 * Persists the grant idempotently and returns the {@code osm_id}s whose row this call actually
	 * created; an id already unlocked for that user is absent from the result and is left as it
	 * was, keeping its original {@code unlocked_at} and {@code source}.
	 *
	 * <p><strong>This method performs NO geometric verification.</strong> It grants exactly what it
	 * is handed, including {@code source = PROXIMITY}, without ever looking at a position, a
	 * distance or a radius. The {@code ST_DWithin} gate is the responsibility of the calling query
	 * — unlocking is defined as a geometric predicate, and that predicate lives nowhere in here.
	 * The name is deliberately ugly so that every call site reads as a claim that the caller has
	 * already proved proximity (or that the user is exempt from it).
	 *
	 * <p>Consequently this method <strong>must never be wired onto an HTTP path</strong>, directly
	 * or through a thin service. A controller that calls it in place of a proximity-gated query
	 * mints {@code granted_by = 'PROXIMITY'} rows for which no proximity was ever measured; such
	 * rows are indistinguishable from legitimate ones, nothing detects them, and there is no
	 * re-lock, so they are permanent. An HTTP unlock must go through a query that carries the
	 * {@code ST_DWithin} predicate itself.
	 *
	 * <p>Existence of each POI is enforced by the foreign key, which makes the statement atomic in
	 * the strict sense: a batch containing one unknown {@code osm_id} persists nothing at all. A
	 * caller that needs an unknown id to be reported rather than to abort the batch must filter
	 * against {@code location_point} in its own query instead.
	 *
	 * <p>An empty {@code osmIds} is accepted and returns an empty set without a round trip's worth
	 * of useful work; unlike {@link #findUnlocked} there is no early return, because no legitimate
	 * caller reaches here with nothing to grant — the HTTP boundary rejects an empty {@code osmIds}
	 * with 400, so the empty case is a bug upstream rather than a shape to optimise for.
	 *
	 * <p><strong>The returned set is a delta, not a state.</strong> {@code RETURNING} after
	 * {@code ON CONFLICT DO NOTHING} yields only the rows this call created, so an id that was
	 * already unlocked is missing from the result even though it <em>is</em> unlocked. A caller
	 * that needs the full unlocked state has to compose it with {@link #findUnlocked}.
	 *
	 * <p>That matters for the response contract fixed for the HTTP unlock endpoint: an
	 * already-unlocked POI belongs in {@code unlocked}, and {@code refused} means strictly "too far
	 * away, or no such POI" — the panel shows state, not the history of one request. So the naive
	 * {@code refused = requested − grantWithoutProximityCheck(...)} is <strong>wrong</strong>: it
	 * drops already-unlocked POIs into {@code refused}. The caller must either union the delta with
	 * {@link #findUnlocked}, or switch to the no-op update idiom
	 * ({@code DO UPDATE SET user_id = poi_unlock.user_id RETURNING osm_id}), which pulls conflicts
	 * into {@code RETURNING} and so returns state directly. Note the trade: that idiom stops
	 * tolerating a duplicate {@code osm_id} within one batch, which {@code DO NOTHING} accepts.
	 */
	Set<String> grantWithoutProximityCheck(UnlockGrant grant);

	/**
	 * Returns the subset of {@code osmIds} already unlocked for {@code userId}. Scoped to one user
	 * by construction — there is no signature here that can return another user's rows.
	 */
	Set<String> findUnlocked(UUID userId, Collection<String> osmIds);
}
