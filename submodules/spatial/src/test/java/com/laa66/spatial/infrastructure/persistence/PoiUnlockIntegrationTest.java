package com.laa66.spatial.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.model.UnlockSource;
import com.laa66.spatial.domain.port.out.UnlockRepository;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;

/**
 * Exercises the unlock port over a real, Flyway-migrated PostGIS container: idempotency, the
 * foreign key, and per-user isolation of the read.
 *
 * <p>Isolation is asserted as a security property, not a convenience: two users unlock different
 * POIs and each read is checked to contain neither the other user's row nor a row for an id that
 * was not asked about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PoiUnlockIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final UUID USER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID USER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static final String POI_A = "node/a";
	private static final String POI_B = "node/b";
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

	@BeforeEach
	void resetTables() {
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE poi_unlock, location_point").update();
		seedPoi(POI_A);
		seedPoi(POI_B);
	}

	@Test
	void grantPersistsOneRowPerPoiAndReturnsWhatItCreated() {
		Set<String> granted = unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(POI_A, POI_B), UnlockSource.PROXIMITY));

		assertThat(granted).containsExactlyInAnyOrder(POI_A, POI_B);
		assertThat(rowCount()).isEqualTo(2);
		assertThat(grantedBy(USER_A, POI_A)).isEqualTo("PROXIMITY");
	}

	@Test
	void repeatedGrantLeavesASingleRowAndReturnsNothingTheSecondTime() {
		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.PROXIMITY));

		Set<String> second = unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.PROXIMITY));

		assertThat(second).isEmpty();
		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * DO NOTHING, not DO UPDATE: the first grant is the fact. Re-granting an already unlocked POI
	 * from a different source must not rewrite why or when it was unlocked — there is no re-lock,
	 * so an overwrite would quietly destroy the only record of how the row came to exist.
	 */
	@Test
	void reGrantFromAnotherSourceLeavesTheOriginalFactUntouched() {
		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.PROXIMITY));
		String firstUnlockedAt = unlockedAt(USER_A, POI_A);

		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.EXEMPT));

		assertThat(grantedBy(USER_A, POI_A)).isEqualTo("PROXIMITY");
		assertThat(unlockedAt(USER_A, POI_A)).isEqualTo(firstUnlockedAt);
	}

	@Test
	void grantOfAnUnknownOsmIdPersistsNothing() {
		assertThatThrownBy(() -> unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(UNKNOWN), UnlockSource.PROXIMITY)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(rowCount()).isZero();
	}

	/**
	 * The foreign key rejects the statement, not the row, so one unknown id aborts the whole batch.
	 * Pinned deliberately: a caller that must report an unknown id instead of failing the batch has
	 * to filter against {@code location_point} in its own query rather than lean on the FK.
	 */
	@Test
	void anUnknownOsmIdAbortsTheWholeBatch() {
		assertThatThrownBy(() -> unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(POI_A, UNKNOWN), UnlockSource.PROXIMITY)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(rowCount()).isZero();
	}

	@Test
	void findUnlockedReturnsOnlyTheRequestedUsersRows() {
		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.PROXIMITY));
		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_B, Set.of(POI_B), UnlockSource.PROXIMITY));

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_A, POI_B)))
				.containsExactly(POI_A);
		assertThat(unlockRepository.findUnlocked(USER_B, List.of(POI_A, POI_B)))
				.containsExactly(POI_B);
		assertThat(unlockRepository.findUnlocked(UUID.randomUUID(), List.of(POI_A, POI_B)))
				.isEmpty();
	}

	@Test
	void findUnlockedIsScopedToTheAskedIds() {
		unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(POI_A, POI_B), UnlockSource.PROXIMITY));

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_B, UNKNOWN)))
				.containsExactly(POI_B);
		assertThat(unlockRepository.findUnlocked(USER_A, List.of())).isEmpty();
	}

	@Test
	void unlockedAtDefaultsToNow() {
		unlockRepository.grantWithoutProximityCheck(new UnlockGrant(USER_A, Set.of(POI_A), UnlockSource.PROXIMITY));

		assertThat(jdbc.sql("""
				SELECT unlocked_at > now() - interval '1 minute'
				FROM poi_unlock WHERE user_id = :user AND osm_id = :poi
				""")
				.param("user", USER_A)
				.param("poi", POI_A)
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
				.param("poi", POI_A)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** The CASCADE production never triggers (the loader only upserts), pinned as the backstop. */
	@Test
	void deletingAPoiCascadesToItsUnlocks() {
		unlockRepository.grantWithoutProximityCheck(
				new UnlockGrant(USER_A, Set.of(POI_A, POI_B), UnlockSource.PROXIMITY));

		jdbc.sql("DELETE FROM location_point WHERE osm_id = :poi").param("poi", POI_A).update();

		assertThat(unlockRepository.findUnlocked(USER_A, List.of(POI_A, POI_B)))
				.containsExactly(POI_B);
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
	 * carries a {@code Set} — so this drives {@code GRANT_SQL} directly. It pins the property, not
	 * the current wiring: {@code ON CONFLICT DO NOTHING} tolerates a second hit on the same row
	 * within one statement, where {@code DO UPDATE} raises "cannot affect row a second time". Both
	 * supports are candidates for change (a JSON body deserializes to a {@code List}, and the no-op
	 * update idiom is on the table for returning state rather than a delta), so the tolerance is
	 * asserted rather than assumed.
	 */
	@Test
	void grantSqlToleratesADuplicateIdWithinOneBatch() {
		assertThat(jdbc.sql(PostgisUnlockAdapter.GRANT_SQL)
				.param("userId", USER_A)
				.param("grantedBy", UnlockSource.PROXIMITY.name())
				.param("osmIds", new String[] { POI_A, POI_A })
				.query(String.class)
				.set()).containsExactly(POI_A);

		assertThat(rowCount()).isEqualTo(1);
	}

	private void seedPoi(String osmId) {
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, category, geom)
				VALUES (:id, :name, :cat, ST_SetSRID(ST_MakePoint(17.03, 51.11), 4326)::geography)
				""")
				.param("id", osmId)
				.param("name", "name-" + osmId)
				.param("cat", Category.MONUMENTS.slug())
				.update();
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
