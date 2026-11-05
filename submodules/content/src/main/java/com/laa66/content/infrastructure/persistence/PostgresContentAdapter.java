package com.laa66.content.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.InvalidGeneratedTextException;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;

/**
 * Postgres adapter for {@code poi_content}: Spring {@link JdbcClient} + native SQL, the same shape
 * spatial's adapters use.
 *
 * <p><strong>Package-private.</strong> Nothing outside this package can name the type, construct
 * one or subclass it, so these statements are reachable only through {@link ContentRepository}.
 *
 * <p><strong>Where correctness lives.</strong> The table's CHECKs stop illegal rows, but they
 * cannot stop a legal row from being written at the wrong moment: nothing in the schema forbids a
 * second lease on a POI another worker is generating, forbids a redelivered result from rewriting
 * finished content, requires a takeover to refresh the lease it just stole, or stops a worker whose
 * lease expired six minutes ago from reporting its result on top of the attempt that replaced it.
 * All four are properties of the statements below, which is why each has a test of its own rather
 * than a comment.
 */
@Repository
class PostgresContentAdapter implements ContentRepository {

	/**
	 * Lease, dedup and takeover in one statement, one round trip, one decision.
	 *
	 * <p>The CTEs answer four different questions and must stay in this order.
	 *
	 * <p><strong>{@code locked}</strong> takes the row lock before anything is decided.
	 * {@code SELECT ... FOR UPDATE} under READ COMMITTED waits for a concurrent writer and then
	 * re-reads the committed version, so the predicates below are evaluated against the row as it is
	 * <em>now</em> and not against this statement's snapshot. Without it the decision is made from a
	 * snapshot that can already be wrong, and wrong in the expensive direction: measured by dropping
	 * the clause, a second caller arriving while the first holds an uncommitted takeover of an
	 * expired lease is told {@code GRANTED} and the row reaches {@code attempts = 3} — two workers,
	 * two provider calls, a second payment for the same POI.
	 * {@code anOrderedRaceToTakeOverAnExpiredLeaseRefusesTheSecondCaller} is the only test in the
	 * module that notices, which is why that test establishes its interleaving through the server
	 * rather than through thread timing.
	 *
	 * <p>{@code existing} is materialised by its many references anyway, and {@code locked} has only
	 * one, so there the keyword rather than the count says it; on both it pins "count once" against
	 * inlining, and no test observes it.
	 *
	 * <p><strong>{@code existing}</strong> names the facts the decision is made of, each written
	 * exactly once: the attempt budget is spent, the lease is over, the row is leasable, the row is
	 * abandoned. The nesting is not decoration — a SELECT list cannot reference its own aliases, and
	 * the alternative is repeating the interval predicates in two places where they would be free to
	 * drift apart.
	 * A missing row makes every value NULL, and NULL is not TRUE, so the refusal is the default.
	 *
	 * <p><strong>{@code leased}</strong> is the lease write. The {@code INSERT} covers "no row yet";
	 * the {@code ON CONFLICT ... DO UPDATE} covers takeover and retry, guarded by the one predicate
	 * computed above. It returns a row if and only if the lease was granted, which is what makes the
	 * outcome unambiguous. It does not run at all on an abandoned row, which {@code parked} writes
	 * instead. Measured without that guard: the statement works only while the final SELECT happens
	 * to evaluate {@code leased} before {@code parked}; reference {@code parked} first and Postgres
	 * raises "ON CONFLICT DO UPDATE command cannot affect row a second time" on every abandoned row.
	 *
	 * <p><strong>{@code parked}</strong> is the one refusal that writes. An abandoned row is
	 * {@code PROCESSING} under an expired lease with its budget spent: the last lease of the window
	 * went out and nobody reported back. It is refused like any exhausted row, but left as it is it
	 * has no class for the answer to carry (the {@code PROCESSING} arm forbids one) and a read keeps
	 * serving {@code PROCESSING} for the rest of the window. So it becomes {@code FAILED} with
	 * {@code TRANSIENT}, {@code started_at} cleared and the budget untouched: the dead token's late
	 * failure is then dropped by the strict fence, while a late success still lands through the
	 * completion's {@code text IS NULL} arm. The class the answer carries is the one this write
	 * returned or, on an exhausted {@code FAILED} row, the one {@code locked} read under the row lock;
	 * both come from this statement, so no second read can see a different row.
	 *
	 * <p><strong>{@code SET started_at = now()} is load-bearing and no constraint enforces it.</strong>
	 * {@code poi_content_state_check} asks a {@code PROCESSING} row for a non-null
	 * {@code started_at}, and a row being taken over already satisfies that with the <em>inherited</em>
	 * timestamp of the attempt that abandoned it. Measured in the M4-02 review: a takeover without
	 * this assignment is accepted by every CHECK, and the takeover predicate immediately answers true
	 * again — the lease does not hold, the next caller steals it, and the POI is generated and paid
	 * for twice. Freshness of the lease is a property of this statement alone, so
	 * {@code aTakenOverLeaseIsFreshEnoughToHoldOffTheNextCaller} exists to kill exactly that mutant.
	 *
	 * <p><strong>Clearing {@code completed_at}, {@code failed_class} and {@code failed_detail} is the
	 * price of the loose state CHECK.</strong> The {@code PROCESSING} arm forbids all three, so a
	 * lease that takes over a terminal row has to clear them in the same UPDATE; a forgotten SET is a
	 * loud constraint violation on the lease rather than a quietly wrong answer. {@code text} is
	 * deliberately kept: a lease must not destroy paid-for text. Kept is not served: the read path
	 * serves text only on {@code COMPLETED}, and serving it during regeneration is M5's decision.
	 *
	 * <p><strong>{@code attempts} and {@code last_attempt_at} move here</strong>, because an attempt
	 * is counted from the lease and not from the provider call (the reasoning is in
	 * {@code V1__content.sql}). The CASE applies {@code ATTEMPT_WINDOW}'s reset, and its boundary is
	 * the exact complement of {@code budget_spent}'s, so the row that is leasable because its window
	 * has passed is the row whose counter restarts at one.
	 *
	 * <p><strong>The {@code RETURNING} trap from M3-04, carried over.</strong>
	 * {@code RETURNING} after {@code ON CONFLICT DO NOTHING} reports only rows this statement
	 * created, so the naive reading "no row came back, therefore someone holds the lease" cannot tell
	 * that case from "already COMPLETED" or from "out of attempts" — three outcomes with three
	 * different answers and three different costs. This statement does not infer the refusal from an
	 * absent row at all: the final SELECT reports the row's prior state alongside the lease result,
	 * and the five outcomes are read off both. {@code theNaiveDoNothingDiscriminatorCannotTellRefusalsApart}
	 * measures the trap instead of describing it.
	 *
	 * <p><strong>{@code RETURNING started_at} is the lease token.</strong> The statement hands back
	 * the timestamp it just wrote, and the two result writes apply only against a row that still
	 * bears it. Without it, "I hold the lease" is a worker's own stale belief, and the row has no way
	 * to tell a result produced by its current attempt from one produced by an attempt it replaced.
	 *
	 * <p><strong>The residual race, deliberately.</strong> {@code locked} finds nothing when another
	 * session created the row a moment ago but has not committed; the INSERT then conflicts, the
	 * guard sees no row and refuses, and the caller is told the lease is held — which is true, since
	 * whoever won the insert holds a fresh lease by construction. The one answer that can lag reality
	 * is a row another session completes in the very instant this statement reads it: the caller is
	 * told the lease is held and learns the truth on its next poll. The error direction is always
	 * towards refusing to generate, never towards generating twice.
	 */
	static final String LEASE_SQL = """
			WITH locked AS MATERIALIZED (
			    SELECT status, attempts, started_at, last_attempt_at, failed_class
			    FROM poi_content
			    WHERE osm_id = :osmId AND mode = :mode AND lang = :lang
			    FOR UPDATE
			),
			existing AS MATERIALIZED (
			    SELECT status,
			           failed_class,
			           budget_spent,
			           status = 'PROCESSING' AND NOT lease_over AS lease_live,
			           status <> 'COMPLETED' AND lease_over AND NOT budget_spent AS leasable,
			           status = 'PROCESSING' AND lease_over AND budget_spent AS abandoned
			    FROM (
			        SELECT status,
			               failed_class,
			               status = 'FAILED'
			                   OR started_at < now() - make_interval(secs => :leaseSeconds) AS lease_over,
			               attempts >= :maxAttempts
			                   AND last_attempt_at >= now() - make_interval(secs => :windowSeconds)
			                   AS budget_spent
			        FROM locked
			    ) evaluated
			),
			leased AS (
			    INSERT INTO poi_content AS target
			        (osm_id, mode, lang, status, attempts, started_at, last_attempt_at)
			    SELECT :osmId, :mode, :lang, 'PROCESSING', 1, now(), now()
			    WHERE NOT coalesce((SELECT abandoned FROM existing), false)
			    ON CONFLICT (osm_id, mode, lang) DO UPDATE
			    SET status          = 'PROCESSING',
			        started_at      = now(),
			        last_attempt_at = now(),
			        attempts        = CASE
			                              WHEN target.last_attempt_at
			                                   < now() - make_interval(secs => :windowSeconds)
			                              THEN 1
			                              ELSE target.attempts + 1
			                          END,
			        completed_at    = NULL,
			        failed_class    = NULL,
			        failed_detail   = NULL
			    WHERE (SELECT leasable FROM existing)
			    RETURNING started_at
			),
			parked AS (
			    UPDATE poi_content
			    SET status        = 'FAILED',
			        failed_class  = 'TRANSIENT',
			        failed_detail = 'lease expired without a result',
			        started_at    = NULL
			    WHERE osm_id = :osmId AND mode = :mode AND lang = :lang
			      AND (SELECT abandoned FROM existing)
			    RETURNING failed_class
			)
			SELECT EXISTS (SELECT 1 FROM leased)                          AS leased,
			       (SELECT started_at FROM leased)                        AS leased_at,
			       (SELECT status FROM existing)                          AS prior_status,
			       coalesce((SELECT lease_live FROM existing), false)     AS lease_live,
			       coalesce((SELECT budget_spent FROM existing), false)   AS budget_spent,
			       coalesce((SELECT failed_class FROM parked),
			                (SELECT failed_class FROM existing))          AS failed_class
			""";

	/**
	 * The successful result write: fenced, idempotent, and unable to lose text that has already been
	 * paid for.
	 *
	 * <p><strong>{@code target.started_at = :leasedAt OR target.text IS NULL} is one condition doing
	 * all three jobs</strong>, and it is deliberately weaker than the fence on the failure path. The
	 * rule it states is <em>a completion may add content it does not own, but it may never replace
	 * content</em>:
	 *
	 * <ul>
	 * <li>The lease that produced the text matches the token and writes. The ordinary path.</li>
	 * <li>A redelivered result finds its own completion already on the row: the completion cleared
	 * {@code started_at}, so the token no longer matches, and the row carries text, so the second arm
	 * is false too. Nothing changes — not the text, not {@code prompt_version}, and above all not
	 * {@code completed_at}, which M4-06 and the client's status machine read as "finished and will not
	 * change". That is the idempotency, and it is now carried by the fence rather than by a separate
	 * {@code status <> 'COMPLETED'} guard, which would have been unreachable behind it.</li>
	 * <li>A worker whose lease expired and was taken over still holds real, paid-for text for this
	 * key. If the row has no text on it, that text is stored: refusing it would throw away
	 * the one thing in this system that cannot be re-obtained for free. It also ends the current
	 * lease, which costs the live attempt only the write-back of a result equivalent to the one now
	 * stored.</li>
	 * <li>If the row does carry text, the same late worker is refused and the row — including the
	 * live attempt's {@code started_at} — is left exactly as it was. Nothing today produces that
	 * shape; regeneration in M5 produces it on every single run, where this arm is what stops a
	 * straggler from reverting fresh content to the text it generated from an older prompt.</li>
	 * </ul>
	 *
	 * <p>It is an upsert and not an UPDATE on purpose: by the time this is called the provider has
	 * been paid, so a missing row — and the only way to get one is somebody deleting it — must not
	 * turn into a silently dropped guide. The fence is in the {@code DO UPDATE} arm, which is the
	 * only arm that can overwrite anything.
	 *
	 * <p>{@code started_at = NULL} releases the lease. The state CHECK tolerates a leftover timestamp
	 * on a finished row, but {@code started_at} answers "who is generating this", and on a finished
	 * row the honest answer is nobody. It is also what makes the token single-use, which is what the
	 * idempotency above rests on.
	 */
	static final String COMPLETE_SQL = """
			INSERT INTO poi_content AS target
			    (osm_id, mode, lang, status, text, prompt_version, completed_at)
			VALUES (:osmId, :mode, :lang, 'COMPLETED', :text, :promptVersion, now())
			ON CONFLICT (osm_id, mode, lang) DO UPDATE
			SET status         = 'COMPLETED',
			    text           = :text,
			    prompt_version = :promptVersion,
			    completed_at   = now(),
			    started_at     = NULL,
			    failed_class   = NULL,
			    failed_detail  = NULL
			WHERE target.started_at = :leasedAt OR target.text IS NULL
			""";

	/**
	 * The failure result write, fenced strictly on the lease token: a failure is recorded against the
	 * lease that produced it, or not at all.
	 *
	 * <p><strong>This is the write that a stale caller must not be allowed to make.</strong> It sets
	 * {@code FAILED} and clears {@code started_at}, which together end the lease and hand the row to
	 * the next caller as retriable. Measured without the fence: a worker whose lease expired reports
	 * its failure after the row was taken over, and the next caller is told {@code RETRY_GRANTED}
	 * with {@code attempts = 3} while the second worker is still generating — a third provider call
	 * and a third payment for one POI, caused entirely by a write-back that was six minutes late.
	 *
	 * <p>Unlike the completion, nothing is lost by refusing a stale one: a failure carries no content,
	 * and the attempt it describes was already counted at its lease. So the condition here is the
	 * strict one, and "success wins" falls out of it rather than needing a clause of its own — a
	 * completion clears {@code started_at}, so every token minted before it is already dead.
	 *
	 * <p><strong>That rests on {@code COMPLETED => started_at IS NULL}, which
	 * {@code poi_content_state_check} does not enforce.</strong> A writer that produces a
	 * {@code COMPLETED} row with a live {@code started_at} reopens the path: measured with a probe, a
	 * failure bearing that token turns finished content into {@code FAILED}. Unreachable today, because
	 * {@link #COMPLETE_SQL} is the only writer of that status and it clears the column; every new path
	 * to {@code COMPLETED} (regeneration in M5, the internal endpoint in M4-11) has to do the same.
	 *
	 * <p>A plain UPDATE, not an upsert: a failure for a row that no longer exists belongs to no lease
	 * and no budget, and re-creating the row would resurrect a dead attempt's state.
	 *
	 * <p>{@code text} is untouched, so a row that failed while regenerating keeps the text of its
	 * previous success — the looseness {@code poi_content_state_check} leaves open for exactly this.
	 * Kept is not served: the read path serves text only on {@code COMPLETED}, and serving it on a
	 * {@code FAILED} row is M5's decision. {@code attempts} is untouched too: the attempt was counted
	 * at the lease.
	 */
	static final String FAIL_SQL = """
			UPDATE poi_content
			SET status        = 'FAILED',
			    failed_class  = :failedClass,
			    failed_detail = :failedDetail,
			    started_at    = NULL
			WHERE osm_id = :osmId AND mode = :mode AND lang = :lang
			  AND started_at = :leasedAt
			""";

	/**
	 * {@link #FAIL_SQL} with a fixed class and detail, plus {@code attempts - 1}. The fence is the
	 * strict one: only the lease that was just taken can give its attempt back, so a stale token can
	 * neither end somebody else's lease nor refund an attempt somebody else spent.
	 */
	static final String RELEASE_SQL = """
			UPDATE poi_content
			SET status        = 'FAILED',
			    failed_class  = 'TRANSIENT',
			    failed_detail = 'generation task not confirmed by the broker',
			    started_at    = NULL,
			    attempts      = attempts - 1
			WHERE osm_id = :osmId AND mode = :mode AND lang = :lang
			  AND started_at = :leasedAt
			""";

	static final String FIND_SQL = """
			SELECT status, text, failed_class
			FROM poi_content
			WHERE osm_id = :osmId AND mode = :mode AND lang = :lang
			""";

	private static final Logger log = LoggerFactory.getLogger(PostgresContentAdapter.class);

	private final JdbcClient jdbcClient;

	PostgresContentAdapter(DataSource dataSource) {
		this.jdbcClient = JdbcClient.create(dataSource);
	}

	@Override
	public GenerationLease leaseGeneration(PoiContentKey key) {
		return key(jdbcClient.sql(LEASE_SQL), key)
				.param("leaseSeconds", GENERATION_LEASE_TIMEOUT.toSeconds())
				.param("windowSeconds", ATTEMPT_WINDOW.toSeconds())
				.param("maxAttempts", MAX_ATTEMPTS_PER_ROW)
				.query((rs, rowNum) -> {
					LeaseOutcome outcome = outcome(rs.getBoolean("leased"), rs.getString("prior_status"),
							rs.getBoolean("lease_live"), rs.getBoolean("budget_spent"));
					return new GenerationLease(outcome,
							token(rs.getObject("leased_at", OffsetDateTime.class)),
							outcome == LeaseOutcome.ATTEMPTS_EXHAUSTED ? failedClass(rs.getString("failed_class")) : null);
				})
				.single();
	}

	@Override
	public void completeGeneration(PoiContentKey key, Instant leasedAt, String text, String promptVersion) {
		// A caller's bug rather than a refused result: a lease that was refused carries no token.
		Objects.requireNonNull(leasedAt, "leasedAt");
		Objects.requireNonNull(promptVersion, "promptVersion");

		// The last point before the row is written, and the only one: the column is unbounded TEXT,
		// so Postgres will store a runaway response in full and serve it as a finished guide, and an
		// empty one as a finished guide with nothing in it. Neither is retried by anything.
		if (text == null || text.isBlank()) {
			throw InvalidGeneratedTextException.blank();
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			throw InvalidGeneratedTextException.tooLong(text.length(), MAX_TEXT_LENGTH);
		}

		int written = key(jdbcClient.sql(COMPLETE_SQL), key)
				.param("leasedAt", timestamp(leasedAt))
				.param("text", text)
				.param("promptVersion", promptVersion)
				.update();

		if (written == 0) {
			// Either a redelivery or paid-for text being dropped, and this line is the only trace of
			// the second. It identifies the row and nothing else: no text, no token.
			log.warn("completion refused for osmId={} mode={} lang={}: row carries text and the lease token does not match",
					key.osmId(), key.mode(), key.lang());
		}
	}

	@Override
	public void failGeneration(PoiContentKey key, Instant leasedAt, FailedClass failedClass, String failedDetail) {
		Objects.requireNonNull(leasedAt, "leasedAt");

		int written = key(jdbcClient.sql(FAIL_SQL), key)
				.param("leasedAt", timestamp(leasedAt))
				.param("failedClass", failedClass.name())
				.param("failedDetail", truncated(failedDetail))
				.update();

		if (written == 0) {
			log.debug("failure dropped for osmId={} mode={} lang={}: row no longer bears the lease token",
					key.osmId(), key.mode(), key.lang());
		}
	}

	@Override
	public void releaseUnpublishedLease(PoiContentKey key, Instant leasedAt) {
		Objects.requireNonNull(leasedAt, "leasedAt");

		int written = key(jdbcClient.sql(RELEASE_SQL), key)
				.param("leasedAt", timestamp(leasedAt))
				.update();

		if (written == 0) {
			log.debug("release dropped for osmId={} mode={} lang={}: row no longer bears the lease token",
					key.osmId(), key.mode(), key.lang());
		}
	}

	@Override
	public Optional<PoiContent> find(PoiContentKey key) {
		return key(jdbcClient.sql(FIND_SQL), key)
				.query((rs, rowNum) -> new PoiContent(
						ContentStatus.valueOf(rs.getString("status")),
						rs.getString("text"),
						failedClass(rs.getString("failed_class"))))
				.optional();
	}

	/**
	 * Reads the five outcomes off what the lease statement reports: whether the lease was granted,
	 * the state the row was in beforehand, whether a live lease is on it, and whether its budget is
	 * spent.
	 *
	 * <p><strong>The order of the refusals is the order of the questions, and two of the three
	 * orderings are load-bearing.</strong> {@code COMPLETED} is answered first, because a finished
	 * row is not "out of attempts", it is done. <em>Liveness is answered before the budget</em>: a
	 * row whose lease is alive is owned by somebody right now, whatever its counter says, and
	 * answering {@code ATTEMPTS_EXHAUSTED} there would tell a client "this will never be generated"
	 * about a POI that is being generated as it asks. Measured before {@code lease_live} existed: a
	 * live lease on a row at the attempt cap answered {@code ATTEMPTS_EXHAUSTED}. The budget is
	 * answered last, and only an <em>expired</em> lease reaches it — such a row is abandoned rather
	 * than owned, so reporting it as held would promise a result nobody will ever produce.
	 *
	 * <p>The trailing {@code HELD_BY_ANOTHER_ATTEMPT} is the conservative default for a refusal the
	 * statement does not explain: refusing to generate is always the cheaper mistake.
	 */
	private static LeaseOutcome outcome(
			boolean leased, String priorStatus, boolean leaseLive, boolean budgetSpent) {
		if (leased) {
			return ContentStatus.FAILED.name().equals(priorStatus)
					? LeaseOutcome.RETRY_GRANTED
					: LeaseOutcome.GRANTED;
		}
		if (priorStatus == null) {
			return LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT;
		}
		if (ContentStatus.COMPLETED.name().equals(priorStatus)) {
			return LeaseOutcome.ALREADY_COMPLETED;
		}
		if (leaseLive) {
			return LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT;
		}
		return budgetSpent ? LeaseOutcome.ATTEMPTS_EXHAUSTED : LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT;
	}

	/**
	 * Truncates the provider's message to what a row may carry. It bounds size only — it cannot make
	 * a detail safe, and the obligation not to build one out of a request URI is the caller's
	 * ({@link ContentRepository#failGeneration}).
	 */
	private static String truncated(String failedDetail) {
		return failedDetail == null || failedDetail.length() <= MAX_FAILED_DETAIL_LENGTH
				? failedDetail
				: failedDetail.substring(0, MAX_FAILED_DETAIL_LENGTH);
	}

	/**
	 * The lease token crosses the boundary as {@code timestamptz}, converted here rather than bound
	 * as an {@link Instant}: the value is only ever compared for equality against the one Postgres
	 * wrote, so the two directions have to agree on the type exactly.
	 */
	private static OffsetDateTime timestamp(Instant leasedAt) {
		return OffsetDateTime.ofInstant(leasedAt, ZoneOffset.UTC);
	}

	private static Instant token(OffsetDateTime leasedAt) {
		return leasedAt == null ? null : leasedAt.toInstant();
	}

	private static JdbcClient.StatementSpec key(JdbcClient.StatementSpec spec, PoiContentKey key) {
		return spec.param("osmId", key.osmId())
				.param("mode", key.mode().name())
				.param("lang", key.lang());
	}

	private static FailedClass failedClass(String value) {
		return value == null ? null : FailedClass.valueOf(value);
	}
}
