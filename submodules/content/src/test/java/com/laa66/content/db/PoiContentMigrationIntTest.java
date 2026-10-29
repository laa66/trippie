package com.laa66.content.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.support.AbstractPostgresIntegrationTest;

/**
 * Boots the real Spring context against Postgres and asserts the table exists, which only holds if
 * Flyway ran at startup. Flyway running through its own API would pass even with the Boot 4
 * auto-configuration module missing from the classpath; this does not.
 *
 * <p>Row isolation and the reason there is no {@code @Transactional} live in
 * {@link AbstractPostgresIntegrationTest}.
 */
@SpringBootTest
class PoiContentMigrationIntTest extends AbstractPostgresIntegrationTest {

	@Test
	void flywayMigratedOnStartup() {
		assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
				.query(String.class).list()).containsExactly("1");
		assertThat(jdbc.sql("SELECT to_regclass('public.poi_content') IS NOT NULL")
				.query(Boolean.class).single()).isTrue();
	}

	@Test
	void primaryKeyIsOsmIdModeLang() {
		assertThat(jdbc.sql("""
				SELECT a.attname
				FROM pg_constraint c
				JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
				JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
				WHERE c.conrelid = 'poi_content'::regclass AND c.contype = 'p'
				ORDER BY k.ord
				""").query(String.class).list()).containsExactly("osm_id", "mode", "lang");
	}

	@Test
	void osmIdWithSlashAndSameKeyIsRejectedTwice() {
		insert("node/42", "TEXT", "pl", "PROCESSING", null);

		assertThatThrownBy(() -> insert("node/42", "TEXT", "pl", "PROCESSING", null))
				.isInstanceOf(DataIntegrityViolationException.class);
		insert("node/42", "AUDIO", "pl", "PROCESSING", null);
		insert("node/42", "TEXT", "en", "PROCESSING", null);
	}

	@Test
	void statusModeAndFailedClassAreChecked() {
		assertThatThrownBy(() -> insert("node/1", "TEXT", "pl", "DONE", null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert("node/1", "VIDEO", "pl", "PROCESSING", null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert("node/1", "TEXT", "pl", "FAILED", "BROKEN"))
				.isInstanceOf(DataIntegrityViolationException.class);

		for (String failedClass : List.of("TRANSIENT", "PERMANENT", "QUOTA")) {
			insert("node/" + failedClass, "TEXT", "pl", "FAILED", failedClass);
		}
	}

	/**
	 * The enumerated failed_class CHECK must admit NULL ({@code NULL IN (...)} is NULL and a CHECK
	 * only rejects FALSE). Every PROCESSING and COMPLETED row depends on it. Stated as its own
	 * criterion because until now it only held as a side effect of other tests.
	 */
	@Test
	void failedClassMayBeNull() {
		insert("node/null-class", "TEXT", "pl", "PROCESSING", null);

		assertThat(jdbc.sql("SELECT failed_class IS NULL FROM poi_content WHERE osm_id = 'node/null-class'")
				.query(Boolean.class).single()).isTrue();
	}

	/**
	 * Keeps the {@link ContentMode} enum and the mode CHECK from drifting apart: they are two
	 * independent copies of the same value set with no codegen between them, and the ITs otherwise
	 * only ever write the literal "TEXT".
	 */
	@Test
	void everyContentModeIsAcceptedByTheModeCheck() {
		for (ContentMode mode : ContentMode.values()) {
			insert("node/mode-" + mode, mode.name(), "pl", "PROCESSING", null);
		}

		assertThat(jdbc.sql("SELECT count(*) FROM poi_content").query(Integer.class).single())
				.isEqualTo(ContentMode.values().length);
	}

	/**
	 * lang is part of the PK, so an unconstrained case would make 'pl' and 'PL' two rows: the same
	 * POI generated (and paid for) twice, with the cache split across both. The CHECK pins the
	 * SHAPE, so a second language stays possible without a migration.
	 */
	@Test
	void langMustBeLowercaseAndTwoLetters() {
		for (String bad : List.of("PL", "Pl", "polish", "p", "")) {
			assertThatThrownBy(() -> insert("node/lang", "TEXT", bad, "PROCESSING", null))
					.as("lang=%s", bad)
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		insert("node/lang", "TEXT", "pl", "PROCESSING", null);
		insert("node/lang", "TEXT", "en", "PROCESSING", null);
	}

	@Test
	void leaseAndQuotaTimestampsAreSeparateColumns() {
		assertThat(jdbc.sql("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'poi_content'
				""").query(String.class).list()).contains("started_at", "last_attempt_at", "completed_at");
	}

	/**
	 * The attempt-counter reset in M4-03 reads "last_attempt_at older than 24 h", which is NULL ->
	 * false on a NULL column: a row that burned its attempts without ever recording one would be
	 * permanently ungeneratable and silent. So the column must be impossible to leave unset — both
	 * on insert (default) and by explicit NULL (NOT NULL) — and the reset predicate must evaluate
	 * to a real boolean on a freshly leased row.
	 */
	@Test
	void lastAttemptAtIsAlwaysSetSoTheQuotaWindowPredicateIsNeverNull() {
		insert("node/lease", "TEXT", "pl", "PROCESSING", null);

		assertThat(jdbc.sql("""
				SELECT (last_attempt_at < now() - interval '24 hours') IS NOT NULL
				FROM poi_content WHERE osm_id = 'node/lease'
				""").query(Boolean.class).single()).isTrue();

		assertThatThrownBy(() -> jdbc.sql("UPDATE poi_content SET last_attempt_at = NULL").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * Lease takeover in M4-03 reads "started_at older than GENERATION_LEASE_TIMEOUT", which is NULL
	 * -> false on a NULL column: a PROCESSING row with no lease timestamp could never be taken over
	 * by anyone, silently. Measured before the fix: that INSERT was accepted. So a PROCESSING row
	 * must be impossible to leave unleased, on insert and by UPDATE -- while a finished row may
	 * legitimately have no lease at all.
	 */
	@Test
	void startedAtIsRequiredOnProcessingSoTheLeaseTakeoverPredicateIsNeverNull() {
		insert("node/lease", "TEXT", "pl", "PROCESSING", null);

		assertThat(jdbc.sql("""
				SELECT (started_at < now() - interval '5 minutes') IS NOT NULL
				FROM poi_content WHERE osm_id = 'node/lease'
				""").query(Boolean.class).single()).isTrue();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, started_at)
				VALUES ('node/unleased', 'TEXT', 'pl', 'PROCESSING', NULL)
				""").update())
				.as("a PROCESSING row with no lease must be rejected on insert")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbc.sql("UPDATE poi_content SET started_at = NULL").update())
				.as("and the lease must not be erasable from an existing PROCESSING row")
				.isInstanceOf(DataIntegrityViolationException.class);

		// A finished row carries no lease: it is over, and the takeover predicate never sees it.
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, text, completed_at)
				VALUES ('node/done-no-lease', 'TEXT', 'pl', 'COMPLETED', 'text', now())
				""").update();
	}

	/**
	 * The state CHECK: status is tied to the columns that status promises. The accepted rows are the
	 * write orders M4-03/M4-11 need (including a retry that keeps the previous text and a failure
	 * that does not destroy it); the rejected rows are the states M4-06's response and M4-12's
	 * status machine have no answer for.
	 */
	@Test
	void theStateCheckSeparatesWritableStatesFromUnanswerableOnes() {
		insertState("ok/processing", "PROCESSING", null, null, false);
		insertState("ok/processing-regen", "PROCESSING", "text from an earlier success", null, false);
		insertState("ok/completed", "COMPLETED", "text", null, true);
		insertState("ok/failed", "FAILED", null, "TRANSIENT", false);
		// The state a retry really produces: the attempt failed, the text from the previous success
		// is still servable. completed_at is NOT part of it -- the row went through the PROCESSING
		// arm to get here, and that arm had to clear it.
		insertState("ok/failed-keeps-earlier-text", "FAILED", "text", "QUOTA", false);

		rejected("bad/failed-without-class", "FAILED", null, null, false);
		rejected("bad/completed-without-text", "COMPLETED", null, null, true);
		rejected("bad/completed-without-timestamp", "COMPLETED", "text", null, false);
		rejected("bad/completed-with-failed-class", "COMPLETED", "text", "PERMANENT", true);
		rejected("bad/processing-with-completed-at", "PROCESSING", null, null, true);
		rejected("bad/processing-with-failed-class", "PROCESSING", null, "TRANSIENT", false);
		// The lease arm of the same CHECK; proven in full by
		// startedAtIsRequiredOnProcessingSoTheLeaseTakeoverPredicateIsNeverNull().
	}

	/** started_at is always set: the state CHECK requires a lease on a PROCESSING row. */
	private void insert(String osmId, String mode, String lang, String status, String failedClass) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, failed_class, started_at)
				VALUES (?, ?, ?, ?, ?, now())
				""")
				.params(osmId, mode, lang, status, failedClass)
				.update();
	}

	private void insertState(String osmId, String status, String text, String failedClass,
			boolean completed) {
		jdbc.sql("""
				INSERT INTO poi_content
					(osm_id, mode, lang, status, text, failed_class, started_at, completed_at)
				VALUES (?, 'TEXT', 'pl', ?, ?, ?, now(), CASE WHEN ?::boolean THEN now() END)
				""")
				.params(osmId, status, text, failedClass, completed)
				.update();
	}

	private void rejected(String osmId, String status, String text, String failedClass,
			boolean completed) {
		assertThatThrownBy(() -> insertState(osmId, status, text, failedClass, completed))
				.as("state must be rejected: %s", osmId)
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
