package com.laa66.content.domain.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.InvalidGeneratedTextException;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;

/**
 * Outbound port for {@code poi_content}: the only way into the table, and the place the generation
 * policy is stated. As in spatial, the adapter behind it is the only place SQL lives.
 *
 * <p>Everything here serves one invariant: <strong>one POI, one mode, one language is generated —
 * and paid for — once.</strong> That is why {@link #leaseGeneration} is a single atomic statement
 * rather than a read followed by a write, and why the policy below is expressed in constants that
 * no deployment can move.
 */
public interface ContentRepository {

	/**
	 * How long a {@code PROCESSING} row stays owned by the attempt that leased it. Past this, the
	 * row is takeover-eligible and the next lease increments {@code attempts}.
	 *
	 * <p><strong>Derived, not picked.</strong> It has to exceed the worst-case wall clock of one
	 * worker delivery plus the time the message spends queued, because a lease shorter than the
	 * worker means the next POST hands the same POI to a second worker: two provider calls, two
	 * publications and <em>a second payment for the same POI</em> — the exact outcome this value
	 * exists to prevent. The worker's worst case is ~84 s (M4-09/M4-10): three provider attempts at
	 * a 20 s timeout, plus the 5 s and 15 s backoffs between them, plus the 4 s minimum spacing
	 * before the call. 5 min leaves roughly 3.5 min of queue delay on top of that, which a
	 * single-consumer worker (CONSUMER_CONCURRENCY = 1) can accumulate behind a handful of messages.
	 *
	 * <p><strong>A constant, not a property, and that is a correctness decision.</strong> Relaxed
	 * binding makes any {@code trippie.*} key env-tunable whether or not anything documents it, and
	 * a {@code Duration} bound from {@code 5} is {@code PT0.005S} — five milliseconds. Every lease
	 * would then be expired on arrival, every POST would hand the POI to another worker, and the
	 * only trace would be a growing bill. Same reasoning as {@code SpatialConfig.AUTH_TIMEOUT},
	 * where the budget is part of the fail-closed contract rather than deployment topology.
	 */
	Duration GENERATION_LEASE_TIMEOUT = Duration.ofMinutes(5);

	/**
	 * How many leases one row may burn inside {@link #ATTEMPT_WINDOW} before it is parked.
	 *
	 * <p>An attempt is counted from the <em>lease</em>, not from the provider call (see the
	 * {@code last_attempt_at} comment in {@code V1__content.sql}): a worker that dies before calling
	 * the provider still burns one. That is the conservative direction — counting from the provider
	 * call is what would let a worker that never reports keep a row's budget alive forever.
	 *
	 * <p>Six leases are up to eighteen provider calls (each delivery retries three times inside its
	 * own lease), after which a client's "Retry" stops producing attempts. Without the cap, "Retry"
	 * is a loop over a paid API; with it, a poisoned POI costs a bounded amount and then goes quiet.
	 */
	int MAX_ATTEMPTS_PER_ROW = 6;

	/**
	 * The window {@link #MAX_ATTEMPTS_PER_ROW} is counted in: a row whose last attempt is older than
	 * this starts again from one. The reset is what keeps a day of provider trouble — a quota day
	 * that ended, an outage that is over — from making a POI permanently ungeneratable, and 24 h is
	 * the period the provider's own daily quota resets on, which is the failure this budget most
	 * often absorbs.
	 *
	 * <p>Written as an interval predicate over {@code last_attempt_at}, which the schema keeps
	 * {@code NOT NULL DEFAULT now()} precisely so this predicate can never evaluate to NULL and
	 * park a row forever.
	 */
	Duration ATTEMPT_WINDOW = Duration.ofHours(24);

	/**
	 * Longest generated text this port will persist, in characters.
	 *
	 * <p>The worker bounds its own output first (M4-10 targets 150–250 Polish words, ~2 kB), so this
	 * is the backstop at the last point before the row is written — and it is the only one: the
	 * column is unbounded {@code TEXT}, so Postgres will happily store a 100 kB response forever.
	 * 16 384 characters leaves an ~8x margin over a legitimate answer, so a normal generation never
	 * meets this limit while a runaway or garbage body does.
	 *
	 * <p>It rejects instead of truncating, see {@link InvalidGeneratedTextException}.
	 */
	int MAX_TEXT_LENGTH = 16_384;

	/**
	 * Longest {@code failed_detail} this port will persist, in characters.
	 *
	 * <p>Unlike the text, this one <strong>truncates</strong>: losing the record that a POI failed is
	 * worse than losing the tail of a provider message, and the failure row is what the retry budget
	 * and the client's answer are both read from.
	 *
	 * <p>It is a bound and not a formality. A provider error carries whatever the client library put
	 * in its message, and a 2 MB body — measured — goes into the row in full, from where it reaches
	 * every operator's log and every database dump. 2 000 characters keeps the part a human reads
	 * (status line, provider error code, first lines of the body) and drops the rest.
	 *
	 * <p>It is a bound on size only. It cannot make a detail safe, so the caller's obligation stands
	 * on its own: see {@link #failGeneration}.
	 */
	int MAX_FAILED_DETAIL_LENGTH = 2_000;

	/**
	 * Claims the right to generate content for one key, in one atomic statement, and says which of
	 * five situations it found. This is the call that decides whether money is spent.
	 *
	 * <p>The lease is taken when there is no row yet, when the row is {@code FAILED} with budget
	 * left, or when it is {@code PROCESSING} but its lease is older than
	 * {@link #GENERATION_LEASE_TIMEOUT}. It is refused when another attempt holds a live lease, when
	 * the content is already {@code COMPLETED}, and when the row has spent
	 * {@link #MAX_ATTEMPTS_PER_ROW} inside {@link #ATTEMPT_WINDOW}.
	 *
	 * <p><strong>Why one statement.</strong> "Read the row, decide, then write" is two snapshots:
	 * two callers both read a {@code FAILED} row, both decide to retry, both publish, and the POI is
	 * paid for twice. There is no way to express this as a check followed by a write that a later
	 * refactor cannot separate; the single statement is the invariant.
	 *
	 * <p><strong>Taking a lease is a write even when it looks like a query.</strong> It sets
	 * {@code started_at} to now (the lease has to be fresh, or the next caller steals it), moves
	 * {@code attempts} and {@code last_attempt_at}, and clears the terminal columns of whatever row
	 * it took over. A caller that leases and then does not generate leaves a row that nobody will
	 * touch for {@link #GENERATION_LEASE_TIMEOUT} and has spent one of its attempts.
	 *
	 * <p><strong>Concurrency.</strong> Two callers racing on the same key produce exactly one lease;
	 * the loser is told {@link LeaseOutcome#HELD_BY_ANOTHER_ATTEMPT}, including when it lost the
	 * creation of the row itself. The one answer the statement may give later than reality is
	 * {@link LeaseOutcome#HELD_BY_ANOTHER_ATTEMPT} for a row another session completed in the same
	 * instant — never the other way round, so the mistake costs a poll and never a generation. The
	 * mechanics are on the adapter's statement.
	 */
	GenerationLease leaseGeneration(PoiContentKey key);

	/**
	 * Records a successful generation, idempotently: the first success is the fact, so a repeated
	 * write of the same key changes nothing — not the text, not {@code prompt_version} and above all
	 * not {@code completed_at}. A redelivered message therefore cannot rewrite finished content, and
	 * it never adds a second row.
	 *
	 * <p>Writes the row if it is somehow gone: the text has already been paid for by the time this
	 * is called, so the write must not be able to drop it on the floor.
	 *
	 * <p><strong>{@code leasedAt} is the token from {@link #leaseGeneration}, and on this path it
	 * protects content rather than the lease.</strong> A worker whose lease expired and was taken
	 * over still arrives holding real, paid-for text for the same key, so a strict fence would throw
	 * away something nobody can get back for free. The rule is therefore narrower than on the failure
	 * path and it is the rule the statement implements: <em>a completion may add content it does not
	 * own, but it may never replace content</em>. A late success lands on a row with nothing servable
	 * on it, and is refused on a row that already carries text — which is both the idempotency of a
	 * redelivered result and, once regeneration exists, the protection of the text a live attempt is
	 * busy replacing.
	 *
	 * <p><strong>{@code promptVersion} is required.</strong> This is the only write that sets the
	 * column and the schema leaves it nullable, so a {@code COMPLETED} row without one would be
	 * accepted and could never say which prompt produced its text (M4-11).
	 *
	 * @throws InvalidGeneratedTextException if {@code text} is null, blank, or longer than
	 *     {@link #MAX_TEXT_LENGTH}
	 * @throws NullPointerException if {@code leasedAt} or {@code promptVersion} is null
	 */
	void completeGeneration(PoiContentKey key, Instant leasedAt, String text, String promptVersion);

	/**
	 * Records a failed generation as a fact in the table rather than as a key with a TTL, so that
	 * the retry budget and the client's answer come from the same place the content does.
	 *
	 * <p>Keeps whatever text an earlier success left: a later failure must not destroy servable
	 * content. For the same reason it never overwrites a {@code COMPLETED} row — success wins over a
	 * failure that arrives after it, which is the only ordering that cannot lose paid-for content.
	 *
	 * <p><strong>{@code leasedAt} is the token from {@link #leaseGeneration} and this path is fenced
	 * strictly by it: a failure is only ever recorded against the lease that produced it.</strong>
	 * Without the fence a worker whose lease had already expired and been taken over could report its
	 * own failure onto the row, which marks it {@code FAILED} and releases a lease it does not hold —
	 * measured: the next caller is then told {@code RETRY_GRANTED} with {@code attempts = 3} while the
	 * second worker is still generating, so the same POI is paid for a third time. A failure carries
	 * nothing that cannot be produced again, so dropping a stale one costs nothing; dropping a stale
	 * success would cost money, which is why the two paths are fenced differently.
	 *
	 * <p>Nothing is written if the row is gone: a failure record for a row that no longer exists
	 * belongs to no lease and no budget.
	 *
	 * <p>{@code failedDetail} is stored for operators and never served — the HTTP contract (M4-06)
	 * returns {@code failedClass} alone — and it is truncated to
	 * {@link #MAX_FAILED_DETAIL_LENGTH} characters.
	 *
	 * <p><strong>The caller must not put a request URI, a query string or request headers into
	 * it.</strong> This string is persisted and ends up in logs and dumps, and a provider client's
	 * exception message routinely carries the URI it called; the provider in M4-10 takes its API key
	 * in a header for exactly this reason, but a detail built out of a URI would put any future
	 * {@code ?key=} straight into the database. Pass the status, the provider's error code and a
	 * body excerpt.
	 *
	 * <p>There is no {@code promptVersion} here and the column is left alone: M4-11 can fail before a
	 * prompt exists, so a {@code FAILED} row legitimately has none, or the version of the text it kept.
	 *
	 * @throws NullPointerException if {@code leasedAt} is null
	 */
	void failGeneration(PoiContentKey key, Instant leasedAt, FailedClass failedClass, String failedDetail);

	/** The row for one key, or empty when nothing has ever been generated for it. */
	Optional<PoiContent> find(PoiContentKey key);
}
