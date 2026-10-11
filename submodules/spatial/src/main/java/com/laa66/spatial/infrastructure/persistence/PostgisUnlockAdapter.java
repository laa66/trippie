package com.laa66.spatial.infrastructure.persistence;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * Postgres adapter for unlock state: Spring {@link JdbcClient} + native SQL, same shape as
 * {@link PostgisLocationAdapter}.
 *
 * <p>Both statements are single prepared statements over a bound {@code text[]} — the write expands
 * the batch with {@code unnest}, the read filters with {@code = ANY(?)} — so a cluster of forty
 * POIs is one statement, one plan and one round trip rather than forty of each. (Per-row work
 * inside the server remains per row: the foreign key fires its trigger once per inserted row.)
 *
 * <p>The array is bound as a plain {@code String[]}; pgjdbc infers {@code text[]} for it in both
 * statements. Deliberately not a {@code java.sql.Array}: building one needs a {@link java.sql.Connection},
 * and a {@code PgArray} holds a reference to the physical connection it was created on, so handing
 * it back to the pool and then binding it on a second checkout is a use-after-release that fails
 * only once the first connection is actually evicted. Deliberately not a {@code List} either —
 * {@code JdbcClient} expands that into placeholders and the {@code text[]} cast no longer parses.
 *
 * <p>The conflict target is named rather than bare: {@code ON CONFLICT (user_id, osm_id)} swallows
 * exactly the re-grant of an already unlocked POI, where a bare {@code ON CONFLICT DO NOTHING}
 * would also silently swallow a violation of some future constraint.
 */
@Repository
public class PostgisUnlockAdapter implements UnlockRepository {

	static final String GRANT_SQL = """
			INSERT INTO poi_unlock (user_id, osm_id, granted_by)
			SELECT :userId, requested.osm_id, :grantedBy
			FROM unnest(:osmIds) AS requested(osm_id)
			ON CONFLICT (user_id, osm_id) DO NOTHING
			RETURNING osm_id
			""";

	static final String FIND_UNLOCKED_SQL = """
			SELECT osm_id
			FROM poi_unlock
			WHERE user_id = :userId
			  AND osm_id = ANY(:osmIds)
			""";

	private final JdbcClient jdbcClient;

	public PostgisUnlockAdapter(DataSource dataSource) {
		this.jdbcClient = JdbcClient.create(dataSource);
	}

	@Override
	public Set<String> grantWithoutProximityCheck(UnlockGrant grant) {
		// No early return on an empty batch, unlike findUnlocked: the HTTP boundary rejects an
		// empty osmIds with 400, so there is no legitimate caller to spare a round trip for.
		return jdbcClient.sql(GRANT_SQL)
				.param("userId", grant.userId())
				.param("grantedBy", grant.source().name())
				.param("osmIds", grant.osmIds().toArray(String[]::new))
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
