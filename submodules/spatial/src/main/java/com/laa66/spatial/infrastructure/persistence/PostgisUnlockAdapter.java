package com.laa66.spatial.infrastructure.persistence;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.model.UnlockSource;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * Postgres adapter for unlock state: Spring {@link JdbcClient} + native SQL, same shape as
 * {@link PostgisLocationAdapter}.
 *
 * <p><strong>Package-private.</strong> Nothing outside this package can name the type, construct
 * one or subclass it, so the SQL is reachable only through {@link UnlockRepository}. That barrier is
 * real but narrow, and the limit is worth knowing: the port itself is public, because the
 * application service depends on it, so a class that injects {@code UnlockRepository} for
 * {@link #findUnlocked} could still call {@link #grant} with a radius of its own choosing. What
 * keeps the HTTP layer out of that is the ArchUnit rule in {@code ArchitectureTest}; what the rule
 * cannot cover is described there and in {@link UnlockRepository#grant}.
 *
 * <p>Both statements are single prepared statements over a bound {@code text[]} — the write selects
 * the batch out of {@code location_point}, the read filters with {@code = ANY(?)} — so a cluster of
 * forty POIs is one statement, one plan and one round trip rather than forty of each.
 *
 * <p>The array is bound as a plain {@code String[]}; pgjdbc infers {@code text[]} for it in both
 * statements. Deliberately not a {@code java.sql.Array}: building one needs a {@link java.sql.Connection},
 * and a {@code PgArray} holds a reference to the physical connection it was created on, so handing
 * it back to the pool and then binding it on a second checkout is a use-after-release that fails
 * only once the first connection is actually evicted. Deliberately not a {@code List} either —
 * {@code JdbcClient} expands that into placeholders and the {@code text[]} cast no longer parses.
 *
 * <p>The conflict target is named rather than bare: {@code ON CONFLICT (user_id, osm_id)} swallows
 * exactly the re-grant of an already unlocked POI, where a bare {@code ON CONFLICT} would also
 * silently swallow a violation of some future constraint.
 */
@Repository
class PostgisUnlockAdapter implements UnlockRepository {

	/**
	 * Gate, write and read-back in one statement, one round trip, one snapshot.
	 *
	 * <p>The gate is the {@code WHERE}: a row can only be inserted for a POI that exists (the join
	 * against {@code location_point}, whose {@code osm_id} primary key also collapses a repeated id
	 * in the request) and that the asserted position reaches. {@code :exempt} short-circuits the
	 * distance test for a user who turned the proximity requirement off — it is the same statement
	 * rather than a second one so that there is exactly one way a {@code poi_unlock} row can come
	 * into existence.
	 *
	 * <p><strong>On the plan, measured rather than assumed.</strong> {@code geom} is
	 * {@code geography(Point,4326)} and a GiST index exists on it, but this statement is not what
	 * keeps that index: the selective predicate here is {@code osm_id = ANY(:osmIds)} over at most
	 * the boundary's cap of 50 ids, so {@code EXPLAIN ANALYZE} on 200k rows shows the generic plan —
	 * which is what pgjdbc gets once {@code prepareThreshold} is passed on a pooled connection —
	 * scanning {@code location_point_pkey} and applying {@code ST_DWithin} as a post-filter. Only
	 * the custom plan of the first few executions reaches for {@code idx_location_point_geom}. Both
	 * cost the same (~390 buffers, ~8 ms), and the PK plan is the better one for a bounded id list,
	 * so this is not a performance note: the GiST index is load-bearing for the {@code nearby} read
	 * path and not for this one.
	 *
	 * <p><strong>{@code DO UPDATE} with a no-op assignment, not {@code DO NOTHING}, and the reason is
	 * concurrency.</strong> {@code RETURNING} after {@code DO NOTHING} skips the conflicting row, so
	 * two parallel requests for the same {@code (user_id, osm_id)} — a retry after a client timeout,
	 * two tabs, two devices — produce a measured wrong answer: the second statement waits on the
	 * first's speculative-insertion lock, then returns no row from the {@code INSERT} arm, while its
	 * {@code UNION} arm reads the pre-statement snapshot in which the first transaction's row does
	 * not yet exist. The POI comes back {@code refused} although it is unlocked and the caller is
	 * standing next to it, which breaks the contract that {@code refused} means strictly "too far
	 * away, or no such POI". {@code DO UPDATE} takes the row lock instead, re-reads the freshly
	 * committed version under READ COMMITTED and carries it into {@code RETURNING}.
	 *
	 * <p>The assignment is deliberately a no-op ({@code user_id} set to the value it already has):
	 * the first grant is the fact, so a repeat must not rewrite {@code unlocked_at} or
	 * {@code granted_by} — those are the only record of how the row came to exist, and there is no
	 * re-lock to correct them with. The price is a new row version on every repeated grant, i.e.
	 * bloat on a path that is written once per POI per user and re-posted only on retries; that is
	 * accepted in exchange for the answer being right.
	 *
	 * <p><strong>The {@code UNION} is what makes the result state rather than the delta</strong> the
	 * {@code RETURNING} clause gives. The second arm reads the pre-existing rows — <strong>scoped to
	 * this user</strong>, which is the only thing standing between one user's unlocks and another
	 * user's response — and does so without the distance predicate, because an unlock is a fact that
	 * distance cannot retract. It is what reports an already-unlocked POI that the current position
	 * no longer reaches, where the {@code INSERT} arm does not even attempt a row.
	 *
	 * <p><strong>The two arms are no longer disjoint, so {@code UNION} rather than {@code UNION ALL}
	 * is load-bearing.</strong> Under {@code DO NOTHING} they could not overlap — one returned new
	 * rows, the other only old ones — and the de-duplication read as a correction. With
	 * {@code DO UPDATE} a re-granted row is returned by both arms, and {@code UNION ALL} would emit
	 * it twice; the {@code Set} collection would still hide that, which is exactly why this is
	 * written down rather than left to be rediscovered.
	 *
	 * <p>That overlap also narrowed what the arm is pinned by. Under {@code DO NOTHING} the repeated
	 * grant tests reddened when it was cut; now they pass through {@code RETURNING}, and the only
	 * tests that fail are the two asserting that an already-unlocked POI is still reported from
	 * outside the radius — {@code PoiUnlockIntegrationTest#anAlreadyUnlockedPoiIsReportedEvenFromOutsideTheRadius}
	 * and its endpoint twin. Those two are the whole guard.
	 *
	 * <p><strong>One race remains, deliberately.</strong> If a row is committed concurrently by
	 * another request while this statement's position does not reach that POI, the {@code INSERT}
	 * arm does not attempt it and only the {@code UNION} arm could answer — and that arm reads the
	 * pre-statement snapshot, so the POI may be reported {@code refused} although it is in fact
	 * unlocked. Reaching it needs "a parallel grant from close by, plus the same id asserted from
	 * out of range, in the same instant"; the next POST reports the POI correctly. This is the known
	 * shape of the gap, not an absence of one.
	 */
	static final String GRANT_SQL = """
			WITH granted AS (
			    INSERT INTO poi_unlock AS target (user_id, osm_id, granted_by)
			    SELECT :userId, point.osm_id, :grantedBy
			    FROM location_point point
			    WHERE point.osm_id = ANY(:osmIds)
			      AND (:exempt OR ST_DWithin(
			              point.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius))
			    ON CONFLICT (user_id, osm_id) DO UPDATE SET user_id = target.user_id
			    RETURNING target.osm_id
			)
			SELECT osm_id FROM granted
			UNION
			SELECT osm_id FROM poi_unlock WHERE user_id = :userId AND osm_id = ANY(:osmIds)
			""";

	static final String FIND_UNLOCKED_SQL = """
			SELECT osm_id
			FROM poi_unlock
			WHERE user_id = :userId
			  AND osm_id = ANY(:osmIds)
			""";

	private final JdbcClient jdbcClient;

	PostgisUnlockAdapter(DataSource dataSource) {
		this.jdbcClient = JdbcClient.create(dataSource);
	}

	@Override
	public Set<String> grant(UnlockGrant grant) {
		// No early return on an empty batch, unlike findUnlocked: the HTTP boundary rejects an
		// empty osmIds with 400, so there is no legitimate caller to spare a round trip for.
		return jdbcClient.sql(GRANT_SQL)
				.param("userId", grant.userId())
				.param("grantedBy", grant.source().name())
				.param("exempt", grant.source() == UnlockSource.EXEMPT)
				.param("osmIds", grant.osmIds().toArray(String[]::new))
				.param("lon", grant.lon())
				.param("lat", grant.lat())
				.param("radius", grant.radiusMeters())
				.query(String.class)
				.set();
	}

	@Override
	public Set<String> findUnlocked(UUID userId, Collection<String> osmIds) {
		// nearby legitimately returns zero items; ANY('{}') would cost a round trip for a
		// result that is known to be empty.
		if (osmIds.isEmpty()) {
			return Set.of();
		}

		return jdbcClient.sql(FIND_UNLOCKED_SQL)
				.param("userId", userId)
				.param("osmIds", osmIds.toArray(String[]::new))
				.query(String.class)
				.set();
	}
}
