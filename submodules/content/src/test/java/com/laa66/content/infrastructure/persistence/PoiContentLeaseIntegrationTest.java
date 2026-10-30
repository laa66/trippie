package com.laa66.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.support.AbstractPostgresIntegrationTest;

/**
 * Exercises the one atomic lease statement over a real, Flyway-migrated Postgres: the four
 * situations it has to tell apart, the takeover of an expired lease, the attempt budget and its
 * window, and the two things no CHECK in the schema can enforce — that a taken-over lease is
 * refreshed, and that a refusal is never confused with a different refusal.
 *
 * <p>Ages are set by writing {@code now() - interval} directly, so the clock the predicates read is
 * the server's own and the tests do not sleep.
 *
 * <p>Row isolation and the reason there is no {@code @Transactional} live in
 * {@link AbstractPostgresIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PoiContentLeaseIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");

	@Autowired
	private ContentRepository repository;

	@Autowired
	private DataSource dataSource;

	// --- the four situations ---------------------------------------------------------------

	@Test
	void theFirstLeaseCreatesTheRowAndCountsTheFirstAttempt() {
		assertThat(lease()).isEqualTo(LeaseOutcome.GRANTED);

		assertThat(status()).isEqualTo("PROCESSING");
		assertThat(attempts()).isEqualTo(1);
		assertThat(bool("started_at IS NOT NULL AND last_attempt_at IS NOT NULL")).isTrue();
		assertThat(rowCount()).isEqualTo(1);
	}

	/** The second caller must not get the lease, and must not spend an attempt either. */
	@Test
	void aSecondLeaseWhileTheFirstIsAliveIsRefusedAndCostsNothing() {
		lease();

		assertThat(lease()).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
		assertThat(attempts()).isEqualTo(1);
	}

	@Test
	void aCompletedRowIsReportedDoneAndIsNotTouched() {
		seedCompleted();
		String completedAt = string("completed_at::text");

		assertThat(lease()).isEqualTo(LeaseOutcome.ALREADY_COMPLETED);

		assertThat(status()).isEqualTo("COMPLETED");
		assertThat(string("completed_at::text")).isEqualTo(completedAt);
		assertThat(string("text")).isEqualTo("finished guide");
		assertThat(attempts()).isEqualTo(1);
	}

	/**
	 * A {@code FAILED} row is retriable, and the lease that retries it has to leave the row in a
	 * state the {@code PROCESSING} arm of {@code poi_content_state_check} accepts: terminal columns
	 * cleared, previous text kept.
	 */
	@Test
	void aFailedRowIsLeasedAgainWithItsTerminalColumnsCleared() {
		seedFailed("TRANSIENT", 1, "0 hours");

		assertThat(lease()).isEqualTo(LeaseOutcome.RETRY_GRANTED);

		assertThat(status()).isEqualTo("PROCESSING");
		assertThat(attempts()).isEqualTo(2);
		assertThat(bool("failed_class IS NULL AND failed_detail IS NULL AND completed_at IS NULL")).isTrue();
		assertThat(string("text")).as("a failure does not destroy servable text").isEqualTo("older guide");
	}

	// --- takeover of an expired lease -------------------------------------------------------

	@Test
	void aProcessingRowWhoseLeaseExpiredIsTakenOverAndSpendsAnAttempt() {
		seedProcessing("6 minutes", 1);

		assertThat(lease()).isEqualTo(LeaseOutcome.GRANTED);

		assertThat(attempts()).isEqualTo(2);
		assertThat(status()).isEqualTo("PROCESSING");
	}

	/**
	 * <strong>The test the M4-02 review asked for by name.</strong> No CHECK can require the lease to
	 * be refreshed: the {@code PROCESSING} arm only asks for a non-null {@code started_at}, which a
	 * row being taken over already satisfies with the inherited timestamp of the attempt that
	 * abandoned it. Measured there: a takeover without {@code SET started_at = now()} is accepted,
	 * the takeover predicate immediately answers true again, and the next caller steals the lease —
	 * a second provider call and a second payment for the same POI.
	 *
	 * <p>So this asserts both halves: the timestamp the statement left behind does not satisfy the
	 * takeover predicate any more, and the behaviour that follows from it — the very next caller is
	 * refused. Dropping {@code started_at = now()} from the statement turns both red.
	 */
	@Test
	void aTakenOverLeaseIsFreshEnoughToHoldOffTheNextCaller() {
		seedProcessing("3 hours", 1);

		assertThat(lease()).isEqualTo(LeaseOutcome.GRANTED);

		assertThat(bool("started_at < now() - make_interval(secs => %d)"
				.formatted(ContentRepository.GENERATION_LEASE_TIMEOUT.toSeconds())))
				.as("the lease we just took must not already be takeover-eligible")
				.isFalse();
		assertThat(lease()).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
		assertThat(attempts()).isEqualTo(2);
	}

	/**
	 * The one row shape the state CHECK permits but no application path produces today: {@code FAILED}
	 * carrying a {@code completed_at}. The lease has to clear it in the same UPDATE or the statement
	 * dies on the CHECK, which is what makes {@code completed_at = NULL} load-bearing rather than
	 * defensive — and it is the shape a regeneration lease over a {@code COMPLETED} row will have in
	 * M5.
	 */
	@Test
	void aLeaseOverARowThatStillCarriesACompletionTimestampClearsIt() {
		jdbc.sql("""
				INSERT INTO poi_content
				    (osm_id, mode, lang, status, text, failed_class, attempts, completed_at)
				VALUES ('node/42', 'TEXT', 'pl', 'FAILED', 'older guide', 'PERMANENT', 1, now())
				""").update();

		assertThat(lease()).isEqualTo(LeaseOutcome.RETRY_GRANTED);

		assertThat(bool("completed_at IS NULL")).isTrue();
	}

	// --- the attempt budget -----------------------------------------------------------------

	@Test
	void aRowThatSpentItsAttemptsInsideTheWindowGetsNoLeaseAndIsNotTouched() {
		seedFailed("QUOTA", ContentRepository.MAX_ATTEMPTS_PER_ROW, "1 hour");

		assertThat(lease()).isEqualTo(LeaseOutcome.ATTEMPTS_EXHAUSTED);

		assertThat(status()).isEqualTo("FAILED");
		assertThat(attempts()).isEqualTo(ContentRepository.MAX_ATTEMPTS_PER_ROW);
	}

	/**
	 * An abandoned {@code PROCESSING} row that is out of budget is reported as exhausted, not as
	 * held: nobody is working on it and nobody will, so answering "a lease is held" would promise a
	 * result that is never coming. This is the half of the pair where the budget decides.
	 */
	@Test
	void anExpiredLeaseOnARowOutOfBudgetIsExhaustedRatherThanHeld() {
		seedProcessing("6 minutes", ContentRepository.MAX_ATTEMPTS_PER_ROW);

		assertThat(lease()).isEqualTo(LeaseOutcome.ATTEMPTS_EXHAUSTED);
	}

	/**
	 * The other half, and the one that was wrong until {@code lease_live} existed: a row at the
	 * attempt cap whose lease is <em>alive</em> is owned by somebody right now, so it is held — not
	 * exhausted. Measured before the fix: this answered {@code ATTEMPTS_EXHAUSTED}, i.e. M4-08 would
	 * have told a client "this POI will never be generated" about a POI being generated as it asked,
	 * and M4-12's status machine would have stopped polling for a result that was on its way.
	 */
	@Test
	void aLiveLeaseOnARowOutOfBudgetIsHeldRatherThanExhausted() {
		seedProcessing("1 minute", ContentRepository.MAX_ATTEMPTS_PER_ROW);

		assertThat(lease()).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
	}

	/** The last attempt in the budget is still an attempt: the cap is a bound, not an off-by-one. */
	@Test
	void theLastAttemptWithinTheBudgetIsStillGranted() {
		seedFailed("TRANSIENT", ContentRepository.MAX_ATTEMPTS_PER_ROW - 1, "1 hour");

		assertThat(lease()).isEqualTo(LeaseOutcome.RETRY_GRANTED);

		assertThat(attempts()).isEqualTo(ContentRepository.MAX_ATTEMPTS_PER_ROW);
		assertThat(lease()).as("and it is the last one").isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
	}

	// --- the lease token --------------------------------------------------------------------

	/**
	 * A granted lease carries the {@code started_at} it wrote, and a refusal carries nothing: the
	 * token is what the two result writes are fenced on, so "no lease" must not hand back something
	 * a caller could write with.
	 */
	@Test
	void aGrantedLeaseCarriesTheTokenItWroteAndARefusalCarriesNone() {
		GenerationLease granted = repository.leaseGeneration(KEY);

		assertThat(granted.outcome()).isEqualTo(LeaseOutcome.GRANTED);
		assertThat(granted.leasedAt()).isEqualTo(startedAt());

		GenerationLease refused = repository.leaseGeneration(KEY);

		assertThat(refused.outcome()).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
		assertThat(refused.leasedAt()).isNull();
	}

	/** Each lease mints its own token, so a taken-over lease cannot be written against by the old one. */
	@Test
	void aTakeoverMintsADifferentTokenThanTheLeaseItReplaced() {
		Instant first = repository.leaseGeneration(KEY).leasedAt();
		jdbc.sql("UPDATE poi_content SET started_at = now() - interval '6 minutes'").update();

		GenerationLease takeover = repository.leaseGeneration(KEY);

		assertThat(takeover.outcome()).isEqualTo(LeaseOutcome.GRANTED);
		assertThat(takeover.leasedAt()).isNotNull().isNotEqualTo(first);
	}

	/** Past the window the budget restarts, so a day of provider trouble cannot park a POI forever. */
	@Test
	void theAttemptCounterRestartsOnceTheWindowHasPassed() {
		seedFailed("QUOTA", ContentRepository.MAX_ATTEMPTS_PER_ROW, "25 hours");

		assertThat(lease()).isEqualTo(LeaseOutcome.RETRY_GRANTED);

		assertThat(attempts()).as("the counter restarts rather than continuing from the cap").isEqualTo(1);
	}

	/** The boundary itself: still inside the window by a minute is still out of budget. */
	@Test
	void theWindowBoundaryIsTheLastAttemptAndNotTheLease() {
		seedFailed("TRANSIENT", ContentRepository.MAX_ATTEMPTS_PER_ROW, "23 hours 59 minutes");

		assertThat(lease()).isEqualTo(LeaseOutcome.ATTEMPTS_EXHAUSTED);
	}

	// --- the RETURNING trap and the key dimensions ------------------------------------------

	/**
	 * The M3-04 trap, measured rather than described. {@code RETURNING} after
	 * {@code ON CONFLICT DO NOTHING} reports only rows the statement created, so "no row came back"
	 * is identical for a live lease, for finished content and for a spent budget — three answers
	 * that differ by a provider call and a client-visible status. This asserts the trap is real on
	 * this table, and that the port answers the three apart anyway.
	 */
	@Test
	void theNaiveDoNothingDiscriminatorCannotTellRefusalsApart() {
		seedCompleted();
		assertThat(naiveLeaseAttempt()).as("no row back from a COMPLETED row").isEmpty();
		assertThat(lease()).isEqualTo(LeaseOutcome.ALREADY_COMPLETED);

		jdbc.sql("DELETE FROM poi_content").update();
		seedProcessing("1 minute", 1);
		assertThat(naiveLeaseAttempt()).as("no row back from a live lease either").isEmpty();
		assertThat(lease()).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);

		jdbc.sql("DELETE FROM poi_content").update();
		seedFailed("QUOTA", ContentRepository.MAX_ATTEMPTS_PER_ROW, "1 hour");
		assertThat(naiveLeaseAttempt()).as("and none from a spent budget").isEmpty();
		assertThat(lease()).isEqualTo(LeaseOutcome.ATTEMPTS_EXHAUSTED);
	}

	/** mode and lang are key dimensions: a lease on one is not a lease on another. */
	@Test
	void aLeaseIsScopedToOneModeAndOneLanguage() {
		assertThat(lease()).isEqualTo(LeaseOutcome.GRANTED);

		assertThat(repository.leaseGeneration(new PoiContentKey("node/42", ContentMode.AUDIO, "pl")).outcome())
				.isEqualTo(LeaseOutcome.GRANTED);
		assertThat(repository.leaseGeneration(new PoiContentKey("node/42", ContentMode.TEXT, "en")).outcome())
				.isEqualTo(LeaseOutcome.GRANTED);
		assertThat(rowCount()).isEqualTo(3);
	}

	// --- concurrency ------------------------------------------------------------------------

	/**
	 * Two callers on the same cold key, through the pool in autocommit. Exactly one lease may be
	 * granted: the loser's {@code ON CONFLICT} path has to refuse although its own snapshot never saw
	 * the winner's row.
	 *
	 * <p><strong>This one is not the guard, and saying so is the point.</strong> Nothing here orders
	 * the two statements, so the window it aims at is microseconds wide and the test passes whether
	 * or not it ever hit it — measured: with the row lock dropped from the statement it stayed green
	 * three runs in a row. It stays because it is the only test that drives two genuinely parallel
	 * autocommit calls through the pool; the ordered tests below are what actually hold the
	 * statement to its promise.
	 */
	@Test
	void twoParallelCallersOnAColdKeyProduceExactlyOneLease() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier startTogether = new CyclicBarrier(2);
		List<LeaseOutcome> outcomes;
		try {
			Future<LeaseOutcome> first = executor.submit(() -> lease(startTogether));
			Future<LeaseOutcome> second = executor.submit(() -> lease(startTogether));
			outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(outcomes).containsExactlyInAnyOrder(
				LeaseOutcome.GRANTED, LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
		assertThat(rowCount()).isEqualTo(1);
		assertThat(attempts()).as("one lease, one attempt").isEqualTo(1);
	}

	/**
	 * The cold-key race with the interleaving established by the server instead of hoped for: the
	 * first session leases and holds its transaction open, the second is started and then waited for
	 * until Postgres itself reports it blocked on a lock, and only then does the first commit.
	 *
	 * <p>The second session therefore conflicts with a row that does not exist in its own snapshot
	 * and must still refuse — the {@code RETURNING}-after-{@code DO NOTHING} trap from M3-04 in its
	 * concurrent form, where the absent row would read as "nothing to generate" instead of "someone
	 * is already generating it".
	 */
	@Test
	void anOrderedRaceOnAColdKeyRefusesTheSecondCaller() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			first.setAutoCommit(false);
			second.setAutoCommit(false);

			assertThat(leaseOn(first)).isEqualTo(LeaseOutcome.GRANTED);

			Future<LeaseOutcome> blocked = executor.submit(() -> leaseOn(second));
			awaitAnotherBackendBlockedOnALock();
			first.commit();

			assertThat(blocked.get(30, TimeUnit.SECONDS)).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
			second.commit();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(rowCount()).isEqualTo(1);
		assertThat(attempts()).as("one lease, one attempt").isEqualTo(1);
	}

	/**
	 * <strong>The test that makes the row lock load-bearing.</strong> Same ordering as above over a
	 * row whose lease has expired, which is the case where a stale read is not merely late but
	 * wrong: the second session's snapshot shows the expired lease, so a statement that decides from
	 * that snapshot takes the lease the first session just took — two workers, two provider calls,
	 * two publications and a second payment for the same POI.
	 *
	 * <p>{@code SELECT ... FOR UPDATE} is what prevents it: under READ COMMITTED it waits for the
	 * first session and then re-reads the committed row, so the predicate runs on the fresh lease
	 * rather than on the stale one. Measured: dropping {@code FOR UPDATE} leaves every other test in
	 * this module green and turns exactly this one red, with {@code GRANTED} and {@code attempts = 3}.
	 */
	@Test
	void anOrderedRaceToTakeOverAnExpiredLeaseRefusesTheSecondCaller() throws Exception {
		seedProcessing("6 minutes", 1);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			first.setAutoCommit(false);
			second.setAutoCommit(false);

			assertThat(leaseOn(first)).isEqualTo(LeaseOutcome.GRANTED);

			Future<LeaseOutcome> blocked = executor.submit(() -> leaseOn(second));
			awaitAnotherBackendBlockedOnALock();
			first.commit();

			assertThat(blocked.get(30, TimeUnit.SECONDS)).isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
			second.commit();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(attempts()).as("one takeover, one attempt spent").isEqualTo(2);
	}

	private LeaseOutcome lease() {
		return repository.leaseGeneration(KEY).outcome();
	}

	private LeaseOutcome lease(CyclicBarrier startTogether) throws Exception {
		startTogether.await(30, TimeUnit.SECONDS);
		return lease();
	}

	/**
	 * Drives the real adapter on one specific connection, so a test can hold its transaction open.
	 * The injected port goes through the pool and gives no control over which connection it uses or
	 * when that connection commits.
	 */
	private LeaseOutcome leaseOn(Connection connection) {
		return new PostgresContentAdapter(new SingleConnectionDataSource(connection, true))
				.leaseGeneration(KEY)
				.outcome();
	}

	/**
	 * Blocks until Postgres reports another backend waiting on a lock for a {@code poi_content}
	 * statement. Asking the server is what removes the race a {@code Thread.sleep} would leave: too
	 * short a sleep lets the first session commit before the second ever conflicts, and the test
	 * then passes against any lease statement at all.
	 */
	private void awaitAnotherBackendBlockedOnALock() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			boolean blocked = jdbc.sql("""
					SELECT count(*) > 0 FROM pg_stat_activity
					WHERE datname = current_database()
					  AND pid <> pg_backend_pid()
					  AND wait_event_type = 'Lock'
					  AND query LIKE '%poi_content%'
					""").query(Boolean.class).single();
			if (blocked) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("the second session never blocked on the first session's lock");
	}

	// --- seeding and reading ----------------------------------------------------------------

	private void seedProcessing(String leaseAge, int attempts) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, attempts, started_at, last_attempt_at)
				VALUES ('node/42', 'TEXT', 'pl', 'PROCESSING', ?, now() - ?::interval, now() - ?::interval)
				""").params(attempts, leaseAge, leaseAge).update();
	}

	private void seedFailed(String failedClass, int attempts, String lastAttemptAge) {
		jdbc.sql("""
				INSERT INTO poi_content
				    (osm_id, mode, lang, status, text, failed_class, failed_detail, attempts, last_attempt_at)
				VALUES ('node/42', 'TEXT', 'pl', 'FAILED', 'older guide', ?, 'provider said no', ?,
				        now() - ?::interval)
				""").params(failedClass, attempts, lastAttemptAge).update();
	}

	private void seedCompleted() {
		jdbc.sql("""
				INSERT INTO poi_content
				    (osm_id, mode, lang, status, text, prompt_version, attempts, completed_at)
				VALUES ('node/42', 'TEXT', 'pl', 'COMPLETED', 'finished guide', 'v1', 1, now())
				""").update();
	}

	private List<Integer> naiveLeaseAttempt() {
		return jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, started_at, last_attempt_at)
				VALUES ('node/42', 'TEXT', 'pl', 'PROCESSING', now(), now())
				ON CONFLICT (osm_id, mode, lang) DO NOTHING
				RETURNING 1
				""").query(Integer.class).list();
	}

	private String status() {
		return string("status");
	}

	private int attempts() {
		return jdbc.sql(select("attempts")).query(Integer.class).single();
	}

	private Instant startedAt() {
		return jdbc.sql(select("started_at")).query(OffsetDateTime.class).single().toInstant();
	}

	private String string(String expression) {
		return jdbc.sql(select(expression)).query(String.class).single();
	}

	private boolean bool(String expression) {
		return jdbc.sql(select(expression)).query(Boolean.class).single();
	}

	private static String select(String expression) {
		return "SELECT %s FROM poi_content WHERE osm_id = 'node/42' AND mode = 'TEXT' AND lang = 'pl'"
				.formatted(expression);
	}

	private int rowCount() {
		return jdbc.sql("SELECT count(*) FROM poi_content").query(Integer.class).single();
	}
}
