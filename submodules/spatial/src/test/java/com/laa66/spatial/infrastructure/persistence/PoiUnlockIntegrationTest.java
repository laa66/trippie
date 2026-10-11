package com.laa66.spatial.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.model.UnlockSource;
import com.laa66.spatial.domain.port.out.UnlockRepository;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * Exercises the unlock port over a real, Flyway-migrated PostGIS container: the geometric gate, the
 * exempt bypass, idempotency, the join that replaces the foreign key as the existence check, and
 * per-user isolation of both the read and the write's read-back.
 *
 * <p>Isolation is asserted as a security property, not a convenience: two users unlock different
 * POIs and each result is checked to contain neither the other user's row nor a row for an id that
 * was not asked about.
 *
 * <p>POIs are placed by {@link PoiSeeder} at an exact geodesic distance from one observer point, so
 * the distances the radius is tested against are produced by the same spheroid that evaluates
 * {@code ST_DWithin} rather than by hand-converted degrees.
 *
 * <p>One test here runs two concurrent transactions on connections of its own, because the
 * conflict clause's behaviour under a parallel grant is not observable through a single session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PoiUnlockIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final UUID USER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID USER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static final double OBSERVER_LAT = 51.11;
	private static final double OBSERVER_LON = 17.03;

	private static final int RADIUS = 50;

	/** 49 m and 51 m from the observer: the two sides of the frozen 50 m radius. */
	private static final String POI_INSIDE = "node/inside";
	private static final String POI_OUTSIDE = "node/outside";
	private static final String POI_FAR = "node/far";
	private static final String UNKNOWN = "node/does-not-exist";

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGIS::getUsername);
		registry.add("spring.datasource.password", POSTGIS::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	@Autowired
	private UnlockRepository unlockRepository;

	@Autowired
	private DataSource dataSource;

	private JdbcClient jdbc;

	private PoiSeeder poi;

	@BeforeEach
	void resetTables() {
		jdbc = JdbcClient.create(dataSource);
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiAtDistance(POI_INSIDE, 49);
		poi.seedPoiAtDistance(POI_OUTSIDE, 51);
		poi.seedPoiAtDistance(POI_FAR, 5000);
	}

	// --- the geometric gate ---------------------------------------------------------------

	@Test
	void proximityGrantTakesWhatIsInsideTheRadiusAndRefusesWhatIsOutside() {
		Set<String> unlocked = unlockRepository.grant(proximityGrant(POI_INSIDE, POI_OUTSIDE, POI_FAR));

		assertThat(unlocked).containsExactly(POI_INSIDE);
		assertThat(rowCount()).isEqualTo(1);
		assertThat(grantedBy(USER_A, POI_INSIDE)).isEqualTo("PROXIMITY");
	}

	/** The radius is a bound, not a scale factor: 5 km away is as refused as 51 m away. */
	@Test
	void proximityGrantOfOnlyDistantPoisPersistsNothing() {
		assertThat(unlockRepository.grant(proximityGrant(POI_OUTSIDE, POI_FAR))).isEmpty();
		assertThat(rowCount()).isZero();
	}

	/**
	 * The exempt flag is the whole difference: same statement, same position, the distance predicate
	 * short-circuited. 5 km is far outside any radius, so nothing but the bypass can explain the row.
	 */
	@Test
	void exemptGrantIgnoresDistanceEntirelyAndRecordsItsSource() {
		Set<String> unlocked = unlockRepository.grant(
				new UnlockGrant(USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_FAR), UnlockSource.EXEMPT));

		assertThat(unlocked).containsExactly(POI_FAR);
		assertThat(grantedBy(USER_A, POI_FAR)).isEqualTo("EXEMPT");
	}

	// --- existence by join, not by foreign key --------------------------------------------

	/**
	 * The lesson of M3-02's {@code anUnknownOsmIdAbortsTheWholeBatch}, now stated positively: the
	 * foreign key rejects the statement rather than the row, so existence has to be checked by the
	 * join against {@code location_point}. An unknown id must be absent from the result while its
	 * batch-mates are still persisted.
	 */
	@Test
	void anUnknownOsmIdIsAbsentFromTheResultWithoutAbortingItsBatch() {
		Set<String> unlocked = unlockRepository.grant(proximityGrant(POI_INSIDE, UNKNOWN));

		assertThat(unlocked).containsExactly(POI_INSIDE);
		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * And on the exempt path too, which is where the trap is easiest to walk back into: an exempt
	 * grant needs no geometry, so it is tempting to select straight out of {@code unnest(:osmIds)}
	 * and let the foreign key sort existence out. That reintroduces exactly the abort this join
	 * exists to avoid — and on the one path where a user has asked for a whole city at once.
	 *
	 * <p><strong>Do not delete this as a duplicate of the test above it.</strong> It is the only test
	 * that sees the behaviour this join exists for. Swapping the join back for
	 * {@code unnest(:osmIds) LEFT JOIN location_point} was measured to redden this test and
	 * {@code grantSqlToleratesADuplicateIdWithinOneBatch} — the latter only because {@code unnest}
	 * does not collapse a repeated id and the statement then fails outright, which says nothing
	 * about existence. The two proximity-path tests survive the mutation entirely, because
	 * {@code ST_DWithin(NULL, ...)} is NULL and the unknown id drops out of the {@code WHERE} before
	 * the foreign key is ever consulted: on the proximity path the join and the FK are
	 * indistinguishable, and only the exempt path, which has no distance predicate to hide behind,
	 * can tell them apart.
	 */
	@Test
	void anUnknownOsmIdIsAbsentFromTheResultOnTheExemptPathToo() {
		Set<String> unlocked = unlockRepository.grant(new UnlockGrant(
				USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_FAR, UNKNOWN), UnlockSource.EXEMPT));

		assertThat(unlocked).containsExactly(POI_FAR);
		assertThat(rowCount()).isEqualTo(1);
	}

	/** The foreign key survives as the schema-level backstop behind that join. */
	@Test
	void foreignKeyStillRejectsARawInsertForAnUnknownPoi() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO poi_unlock (user_id, osm_id, granted_by)
				VALUES (:user, :poi, 'PROXIMITY')
				""")
				.param("user", USER_A)
				.param("poi", UNKNOWN)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- idempotency, and state rather than a delta ---------------------------------------

	@Test
	void repeatedGrantLeavesASingleRowAndStillReportsThePoiAsUnlocked() {
		unlockRepository.grant(proximityGrant(POI_INSIDE));

		Set<String> second = unlockRepository.grant(proximityGrant(POI_INSIDE));

		assertThat(second).containsExactly(POI_INSIDE);
		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * The concurrency case {@code DO UPDATE} exists for, ordered rather than hoped for.
	 *
	 * <p>Two sessions grant the same {@code (user_id, osm_id)} for a POI 49 m away. The first holds
	 * its transaction open; the second is started and then waited for until Postgres itself reports
	 * it blocked on a lock, so the interleaving is established by the server and not by a sleep that
	 * could pass vacuously. Only then does the first commit.
	 *
	 * <p>Under {@code DO NOTHING} the second session answers {@code refused} for a POI it is standing
	 * next to: {@code RETURNING} skips the conflicting row, and the {@code UNION} arm reads the
	 * snapshot from before its own statement, in which the other session's row does not exist.
	 * {@code DO UPDATE} takes the row lock, re-reads the committed version under READ COMMITTED and
	 * returns it. Real triggers are a retry after a client timeout, two tabs and two devices — the
	 * per-POI client-side dedup of M3-07 is per client instance and does not span them.
	 */
	@Test
	void aConcurrentGrantOfTheSamePoiStillReportsItAsUnlocked() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			first.setAutoCommit(false);
			second.setAutoCommit(false);

			assertThat(grantOn(first, POI_INSIDE)).containsExactly(POI_INSIDE);

			Future<Set<String>> blocked = executor.submit(() -> grantOn(second, POI_INSIDE));
			awaitAnotherBackendBlockedOnALock();
			first.commit();

			assertThat(blocked.get(30, TimeUnit.SECONDS)).containsExactly(POI_INSIDE);
			second.commit();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * An unlock is a fact and there is no re-lock, so walking away cannot retract it: the POI is
	 * reported as unlocked from 5 km off, and the distance predicate the same statement applies to
	 * new grants must not reach back over it.
	 */
	@Test
	void anAlreadyUnlockedPoiIsReportedEvenFromOutsideTheRadius() {
		unlockRepository.grant(
				new UnlockGrant(USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_FAR), UnlockSource.EXEMPT));

		assertThat(unlockRepository.grant(proximityGrant(POI_FAR, POI_OUTSIDE))).containsExactly(POI_FAR);
		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * The first grant is the fact. {@code ON CONFLICT} is {@code DO UPDATE}, for the concurrency
	 * reason spelled out on {@code GRANT_SQL}, but the assignment is a no-op — so re-granting an
	 * already unlocked POI from a different source must still not rewrite why or when it was
	 * unlocked; there is no re-lock, so an overwrite would quietly destroy the only record of how
	 * the row came to exist.
	 *
	 * <p>This is the test that pins that, and it is the one that stands between the no-op assignment
	 * and a real one: mutating the clause to
	 * {@code DO UPDATE SET granted_by = EXCLUDED.granted_by, unlocked_at = now()} turns this test and
	 * only this test red.
	 */
	@Test
	void reGrantFromAnotherSourceLeavesTheOriginalFactUntouched() {
		unlockRepository.grant(proximityGrant(POI_INSIDE));
		String firstUnlockedAt = unlockedAt(USER_A, POI_INSIDE);

		unlockRepository.grant(new UnlockGrant(
				USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_INSIDE), UnlockSource.EXEMPT));

		assertThat(grantedBy(USER_A, POI_INSIDE)).isEqualTo("PROXIMITY");
		assertThat(unlockedAt(USER_A, POI_INSIDE)).isEqualTo(firstUnlockedAt);
	}

	// --- per-user isolation ----------------------------------------------------------------

	/**
	 * The security property of the write's read-back: the UNION arm reads {@code poi_unlock}, so
	 * dropping its {@code user_id} predicate would report another user's unlock as this user's.
	 * Asserted from outside the radius, where nothing but that arm can produce an answer.
	 */
	@Test
	void grantNeverReportsAnotherUsersUnlock() {
		unlockRepository.grant(new UnlockGrant(
				USER_B, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_FAR), UnlockSource.EXEMPT));

		assertThat(unlockRepository.grant(proximityGrant(POI_FAR))).isEmpty();
		assertThat(unlockRepository.grant(new UnlockGrant(
				USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_INSIDE), UnlockSource.PROXIMITY)))
				.containsExactly(POI_INSIDE);
		assertThat(rowCount()).isEqualTo(2);
	}

	@Test
	void findUnlockedReturnsOnlyTheRequestedUsersRows() {
		unlockRepository.grant(proximityGrant(POI_INSIDE));
		unlockRepository.grant(new UnlockGrant(
				USER_B, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_OUTSIDE), UnlockSource.EXEMPT));

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_INSIDE, POI_OUTSIDE)))
				.containsExactly(POI_INSIDE);
		assertThat(unlockRepository.findUnlocked(USER_B, List.of(POI_INSIDE, POI_OUTSIDE)))
				.containsExactly(POI_OUTSIDE);
		assertThat(unlockRepository.findUnlocked(UUID.randomUUID(), List.of(POI_INSIDE, POI_OUTSIDE)))
				.isEmpty();
	}

	@Test
	void findUnlockedIsScopedToTheAskedIds() {
		unlockRepository.grant(new UnlockGrant(
				USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(POI_INSIDE, POI_OUTSIDE), UnlockSource.EXEMPT));

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_OUTSIDE, UNKNOWN)))
				.containsExactly(POI_OUTSIDE);
		assertThat(unlockRepository.findUnlocked(USER_A, List.of())).isEmpty();
	}

	// --- schema and statement properties ---------------------------------------------------

	@Test
	void unlockedAtDefaultsToNow() {
		unlockRepository.grant(proximityGrant(POI_INSIDE));

		assertThat(jdbc.sql("""
				SELECT unlocked_at > now() - interval '1 minute'
				FROM poi_unlock WHERE user_id = :user AND osm_id = :poi
				""")
				.param("user", USER_A)
				.param("poi", POI_INSIDE)
				.query(Boolean.class)
				.single()).isTrue();
	}

	/**
	 * Mirrors the category CHECK: the DB is the enforcement point for a contract with no shared
	 * codegen artifact, so the allowed set is pinned by introspection — writing the two known
	 * values cannot detect a third being added.
	 */
	@Test
	void grantedByCheckPinsExactlyTheTwoSources() {
		assertThat(jdbc.sql("""
				SELECT unnest(regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z]+)''::text', 'g'))
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'public' AND t.relname = 'poi_unlock'
				  AND c.conname = 'poi_unlock_granted_by_check'
				""").query(String.class).list())
				.containsExactlyInAnyOrder("PROXIMITY", "EXEMPT");

		assertThat(UnlockSource.values()).extracting(Enum::name)
				.containsExactlyInAnyOrder("PROXIMITY", "EXEMPT");

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO poi_unlock (user_id, osm_id, granted_by)
				VALUES (:user, :poi, 'ADMIN')
				""")
				.param("user", USER_A)
				.param("poi", POI_INSIDE)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** The CASCADE production never triggers (the loader only upserts), pinned as the backstop. */
	@Test
	void deletingAPoiCascadesToItsUnlocks() {
		unlockRepository.grant(new UnlockGrant(USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS,
				Set.of(POI_INSIDE, POI_OUTSIDE), UnlockSource.EXEMPT));

		jdbc.sql("DELETE FROM location_point WHERE osm_id = :poi").param("poi", POI_INSIDE).update();

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_INSIDE, POI_OUTSIDE)))
				.containsExactly(POI_OUTSIDE);
	}

	/**
	 * Pinned exactly, not just "the PK exists": both consumers' predicates are covered by the
	 * composite PK, and a speculative index on osm_id would cost every write for an access pattern
	 * nothing performs. An anyMatch-style assertion cannot see one being added.
	 */
	@Test
	void tableHasExactlyThePrimaryKeyIndex() {
		assertThat(jdbc.sql("""
				SELECT indexname FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'poi_unlock'
				""").query(String.class).list()).containsExactly("poi_unlock_pkey");

		assertThat(jdbc.sql("""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'poi_unlock'
				""").query(String.class).single()).contains("(user_id, osm_id)");
	}

	/**
	 * A duplicate id inside one batch is structurally impossible through the port — {@link UnlockGrant}
	 * carries a {@code Set}, and the HTTP boundary de-duplicates the body's list — so this drives
	 * {@code GRANT_SQL} directly.
	 *
	 * <p>What keeps it harmless is one support, and this test pins exactly that one:
	 * {@code location_point.osm_id} is a primary key, so the join collapses the repeated id to a
	 * single row <em>before</em> the insert, and the conflict clause never sees the same row twice.
	 * That matters more than it did under {@code DO NOTHING}, which tolerated a second hit by
	 * itself: with {@code DO UPDATE} a second hit would raise "cannot affect row a second time", so
	 * the join is now the only thing standing between a duplicated id and a failed statement.
	 *
	 * <p>It says nothing about the choice of {@code DO NOTHING} versus {@code DO UPDATE} — that is
	 * pinned on the concurrency side by {@code aConcurrentGrantOfTheSamePoiStillReportsItAsUnlocked}
	 * and on the immutability side by {@code reGrantFromAnotherSourceLeavesTheOriginalFactUntouched}.
	 */
	@Test
	void grantSqlToleratesADuplicateIdWithinOneBatch() {
		assertThat(jdbc.sql(PostgisUnlockAdapter.GRANT_SQL)
				.param("userId", USER_A)
				.param("grantedBy", UnlockSource.PROXIMITY.name())
				.param("exempt", false)
				.param("osmIds", new String[] { POI_INSIDE, POI_INSIDE })
				.param("lon", OBSERVER_LON)
				.param("lat", OBSERVER_LAT)
				.param("radius", RADIUS)
				.query(String.class)
				.set()).containsExactly(POI_INSIDE);

		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * Pins {@link PoiSeeder}, not the radius. {@code ST_Project} and {@code ST_Distance} invert each
	 * other on the same spheroid by construction, so this agrees to twelve decimal places and could
	 * never have failed by a hair — it is not evidence that 49 m and 51 m straddle the threshold, and
	 * it is insensitive to the PostGIS version.
	 *
	 * <p>Its value is the one failure it can catch, and that failure is plausible: the seeder being
	 * rewritten to offset by hand-converted degrees, or being pointed at a different observer, which
	 * would move every distance in this class and in the two endpoint tests that share the helper
	 * while all their assertions kept passing for the wrong reason.
	 */
	@Test
	void seedPlacesPoisAtTheIntendedGeodesicDistances() {
		assertThat(poi.distanceFromObserver(POI_INSIDE)).isCloseTo(49, Offset.offset(0.5));
		assertThat(poi.distanceFromObserver(POI_OUTSIDE)).isCloseTo(51, Offset.offset(0.5));
	}

	/**
	 * Drives {@code GRANT_SQL} on one specific connection, so a test can hold a transaction open.
	 * The port itself goes through the pool and gives no control over which connection, or when it
	 * commits.
	 */
	private Set<String> grantOn(Connection connection, String osmId) {
		return JdbcClient.create(new SingleConnectionDataSource(connection, true))
				.sql(PostgisUnlockAdapter.GRANT_SQL)
				.param("userId", USER_A)
				.param("grantedBy", UnlockSource.PROXIMITY.name())
				.param("exempt", false)
				.param("osmIds", new String[] { osmId })
				.param("lon", OBSERVER_LON)
				.param("lat", OBSERVER_LAT)
				.param("radius", RADIUS)
				.query(String.class)
				.set();
	}

	/**
	 * Blocks until Postgres reports some other backend waiting on a lock for a {@code poi_unlock}
	 * statement. Asking the server removes the race a {@code Thread.sleep} would leave: too short a
	 * sleep would let the first session commit before the second ever conflicted, and the test would
	 * then pass on any conflict clause at all.
	 */
	private void awaitAnotherBackendBlockedOnALock() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			boolean blocked = jdbc.sql("""
					SELECT count(*) > 0 FROM pg_stat_activity
					WHERE datname = current_database()
					  AND pid <> pg_backend_pid()
					  AND wait_event_type = 'Lock'
					  AND query LIKE '%poi_unlock%'
					""").query(Boolean.class).single();
			if (blocked) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("the second session never blocked on the first session's row lock");
	}

	private UnlockGrant proximityGrant(String... osmIds) {
		return new UnlockGrant(
				USER_A, OBSERVER_LAT, OBSERVER_LON, RADIUS, Set.of(osmIds), UnlockSource.PROXIMITY);
	}

	private int rowCount() {
		return jdbc.sql("SELECT count(*) FROM poi_unlock").query(Integer.class).single();
	}

	private String grantedBy(UUID userId, String osmId) {
		return jdbc.sql("SELECT granted_by FROM poi_unlock WHERE user_id = :user AND osm_id = :poi")
				.param("user", userId)
				.param("poi", osmId)
				.query(String.class)
				.single();
	}

	private String unlockedAt(UUID userId, String osmId) {
		return jdbc.sql("SELECT unlocked_at::text FROM poi_unlock WHERE user_id = :user AND osm_id = :poi")
				.param("user", userId)
				.param("poi", osmId)
				.query(String.class)
				.single();
	}
}
