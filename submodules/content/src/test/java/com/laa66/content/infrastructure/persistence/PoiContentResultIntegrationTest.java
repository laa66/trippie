package com.laa66.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.InvalidGeneratedTextException;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.support.AbstractPostgresIntegrationTest;

/**
 * The other half of the port: writing a generation result and reading a row back by its key.
 *
 * <p>The properties under test are the ones a redelivered message, a late failure or a worker whose
 * lease expired mid-generation can break — the first success is the fact, paid-for text is never
 * dropped or silently shortened, and a result is only ever written against the lease that produced
 * it.
 *
 * <p>"Zombie" below means the one worker this system really produces: its lease expired, another
 * attempt took the row over, and it then reports a result it has already paid for. The two result
 * paths treat it differently on purpose, and each direction has its own test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class PoiContentResultIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");

	@Autowired
	private ContentRepository repository;

	// --- the successful result --------------------------------------------------------------

	@Test
	void aSuccessIsStoredAndReadBackByKey() {
		Instant leasedAt = lease();

		repository.completeGeneration(KEY, leasedAt, "a guide to the town hall", "v1");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.COMPLETED, "a guide to the town hall", null));
		assertThat(bool("completed_at IS NOT NULL AND started_at IS NULL AND prompt_version = 'v1'"))
				.as("the lease is released and the prompt version is recorded with the row")
				.isTrue();
	}

	/**
	 * A redelivered result must change nothing. {@code completed_at} is the one M4-06 and the client
	 * status machine read as "finished and final", so a second write moving it — or adding a second
	 * row — is what this pins. The mechanism is the fence: the first write cleared {@code started_at},
	 * so the token the caller still holds no longer matches anything.
	 */
	@Test
	void aRepeatedSuccessfulWriteChangesNothingAndAddsNoRow() {
		Instant leasedAt = lease();
		repository.completeGeneration(KEY, leasedAt, "the first text", "v1");
		String completedAt = string("completed_at::text");

		repository.completeGeneration(KEY, leasedAt, "a different text", "v2");

		assertThat(string("completed_at::text")).isEqualTo(completedAt);
		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.COMPLETED, "the first text", null));
		assertThat(string("prompt_version")).isEqualTo("v1");
		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * The text is paid for before this call, so the write may not depend on the row still being
	 * there. Nothing in the system deletes rows today, which is exactly why this would be a silent
	 * loss the first time something does.
	 */
	@Test
	void aSuccessWithNoRowLeftToUpdateIsStillStored() {
		Instant leasedAt = lease();
		jdbc.sql("DELETE FROM poi_content").update();

		repository.completeGeneration(KEY, leasedAt, "a guide whose row went away", "v1");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.COMPLETED, "a guide whose row went away", null));
	}

	/**
	 * Rejected at the boundary, not truncated: the column is unbounded {@code TEXT}, so a 100 kB
	 * response would be stored in full, and a shortened one would be served as a finished guide that
	 * nothing ever retries. Nothing may be persisted either.
	 */
	@Test
	void anOverlongTextIsRejectedRatherThanStoredOrShortened() {
		Instant leasedAt = lease();
		String tooLong = "x".repeat(100_000);

		assertThatThrownBy(() -> repository.completeGeneration(KEY, leasedAt, tooLong, "v1"))
				.isInstanceOf(InvalidGeneratedTextException.class)
				.hasMessageContaining(String.valueOf(ContentRepository.MAX_TEXT_LENGTH));

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.PROCESSING, null, null));
	}

	@Test
	void aTextAtTheLimitIsAccepted() {
		Instant leasedAt = lease();

		repository.completeGeneration(KEY, leasedAt, "x".repeat(ContentRepository.MAX_TEXT_LENGTH), "v1");

		assertThat(status()).isEqualTo("COMPLETED");
	}

	/**
	 * An empty text would be stored as a {@code COMPLETED} row with nothing in it: a finished guide
	 * that renders blank, that M4-12's status machine stops polling for, and that nothing retries
	 * because {@code COMPLETED} is terminal. A null one used to be a bare NPE from the length check.
	 * Both are the same decision for the caller — a permanent failure — so both are the same typed
	 * refusal, and neither writes anything.
	 */
	@Test
	void aMissingOrBlankTextIsRefusedAndLeavesTheLeaseAlone() {
		Instant leasedAt = lease();

		for (String blank : new String[] { null, "", "   ", "\n\t " }) {
			assertThatThrownBy(() -> repository.completeGeneration(KEY, leasedAt, blank, "v1"))
					.as("text=%s", blank == null ? "null" : "'" + blank + "'")
					.isInstanceOf(InvalidGeneratedTextException.class);
		}

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.PROCESSING, null, null));
		assertThat(startedAt()).isEqualTo(leasedAt);
	}

	// --- fencing: the worker whose lease expired while it was working -----------------------

	/**
	 * <strong>The scenario the lease token exists for.</strong> A {@code PROCESSING} row is taken over
	 * after its lease expires, and the first worker — still alive, still generating — then reports
	 * its failure.
	 *
	 * <p>Unfenced, measured, that write marks the row {@code FAILED} and clears {@code started_at},
	 * which releases a lease it does not hold: the next caller is told {@code RETRY_GRANTED} and the
	 * row reaches {@code attempts = 3} while the second worker is still running, so one POI is
	 * generated and paid for a third time. Fenced, the stale write does nothing at all and the row
	 * stays owned by the attempt that owns it.
	 */
	@Test
	void aZombieWorkersFailureCannotEndTheLeaseThatReplacedIt() {
		Instant expired = lease();
		expireTheLease();
		Instant live = repository.leaseGeneration(KEY).leasedAt();

		repository.failGeneration(KEY, expired, FailedClass.TRANSIENT, "a failure six minutes late");

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.PROCESSING, null, null));
		assertThat(startedAt()).as("the live attempt still owns the row").isEqualTo(live);
		assertThat(attempts()).isEqualTo(2);
		assertThat(repository.leaseGeneration(KEY).outcome())
				.as("and the row is not handed to a third attempt")
				.isEqualTo(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT);
		assertThat(attempts()).isEqualTo(2);
	}

	/**
	 * The same zombie, reporting a success instead, and the reason the completion is fenced more
	 * weakly: this worker holds real text that the provider was already paid for. The row has nothing
	 * servable on it, so storing that text destroys nothing and saves a generation — refusing it
	 * would throw away the one thing here that cannot be re-obtained for free.
	 */
	@Test
	void aZombieWorkersSuccessStillStoresPaidTextWhenNothingWouldBeLost() {
		Instant expired = lease();
		expireTheLease();
		repository.leaseGeneration(KEY);

		repository.completeGeneration(KEY, expired, "text the first worker had already paid for", "v1");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.COMPLETED, "text the first worker had already paid for", null));
	}

	/**
	 * The limit of that leniency, and the half the {@code text IS NULL} arm exists for: a completion
	 * may add content it does not own, but it may never replace content. Nothing produces this row
	 * shape today — a {@code PROCESSING} row carrying text from an earlier success — and regeneration
	 * in M5 produces it on every run, where an unfenced straggler would silently revert fresh content
	 * to what it generated from an older prompt. The live attempt's lease must also survive, or the
	 * straggler would still have ended it.
	 */
	@Test
	void aZombieWorkersSuccessCannotReplaceTextItDoesNotOwn() {
		Instant expired = lease();
		repository.completeGeneration(KEY, expired, "the earlier guide", "v1");
		Instant live = regenerateUnderANewLease();

		repository.completeGeneration(KEY, expired, "the straggler's text", "v0");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.PROCESSING, "the earlier guide", null));
		assertThat(startedAt()).as("the regenerating attempt keeps its lease").isEqualTo(live);
	}

	/** And the attempt that does own the lease replaces that text, which is the point of a retry. */
	@Test
	void aRegenerationWithItsOwnLeaseReplacesTheEarlierText() {
		Instant first = lease();
		repository.completeGeneration(KEY, first, "the earlier guide", "v1");
		Instant live = regenerateUnderANewLease();

		repository.completeGeneration(KEY, live, "the regenerated guide", "v2");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.COMPLETED, "the regenerated guide", null));
	}

	/**
	 * The zero that matters comes from the real statement, not from a stub: this is the shape in which
	 * paid-for text is dropped, and the warning is the only trace it leaves. It names the row and
	 * carries none of the text.
	 */
	@Test
	void aRefusedCompletionWarnsWithTheRowAndWithoutTheText(CapturedOutput output) {
		Instant expired = lease();
		repository.completeGeneration(KEY, expired, "the earlier guide", "v1");
		regenerateUnderANewLease();

		repository.completeGeneration(KEY, expired, "the straggler's text", "v0");

		assertThat(output.getOut())
				.containsPattern("WARN.*completion refused for osmId=node/42 mode=TEXT lang=pl")
				.doesNotContain("the straggler's text");
	}

	@Test
	void anAcceptedCompletionDoesNotWarn(CapturedOutput output) {
		repository.completeGeneration(KEY, lease(), "a guide to the town hall", "v1");

		assertThat(output.getOut()).doesNotContain("completion refused");
	}

	/** On the failure path a zero is the zombie doing what zombies do, so it stays out of WARN. */
	@Test
	void aRefusedFailureDoesNotWarn(CapturedOutput output) {
		Instant expired = lease();
		expireTheLease();
		repository.leaseGeneration(KEY);

		repository.failGeneration(KEY, expired, FailedClass.TRANSIENT, "a failure six minutes late");

		assertThat(output.getOut()).doesNotContainPattern("WARN.*osmId=node/42");
	}

	// --- the failed result ------------------------------------------------------------------

	@Test
	void aFailureIsStoredWithItsClassAndReleasesTheLease() {
		Instant leasedAt = lease();

		repository.failGeneration(KEY, leasedAt, FailedClass.TRANSIENT, "provider timed out");

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.FAILED, null, FailedClass.TRANSIENT));
		assertThat(bool("started_at IS NULL AND failed_detail = 'provider timed out'")).isTrue();
		assertThat(attempts()).as("the attempt was counted at the lease, not here").isEqualTo(1);
	}

	/**
	 * Success wins, and with the fence in place it wins for a structural reason rather than by a
	 * clause of its own: a completion clears {@code started_at}, so every token minted before it is
	 * already dead. A redelivered failure cannot turn finished content into a {@code FAILED} row.
	 */
	@Test
	void aFailureArrivingAfterASuccessIsDropped() {
		Instant leasedAt = lease();
		repository.completeGeneration(KEY, leasedAt, "the finished guide", "v1");

		repository.failGeneration(KEY, leasedAt, FailedClass.PERMANENT, "late failure");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.COMPLETED, "the finished guide", null));
	}

	/** A failure while regenerating keeps the previous success servable. */
	@Test
	void aFailureKeepsTheTextOfAnEarlierSuccess() {
		Instant first = lease();
		repository.completeGeneration(KEY, first, "the earlier guide", "v1");
		Instant live = regenerateUnderANewLease();

		repository.failGeneration(KEY, live, FailedClass.QUOTA, "out of quota");

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.FAILED, "the earlier guide", FailedClass.QUOTA));
	}

	/**
	 * Nothing is written when the row is gone: a failure record belonging to no lease and no budget
	 * has nothing to attach itself to, and re-creating the row would resurrect a dead attempt's
	 * state. This is the one asymmetry with the success path, where the row is re-created because the
	 * text would otherwise be lost.
	 */
	@Test
	void aFailureForARowThatIsGoneWritesNothing() {
		Instant leasedAt = lease();
		jdbc.sql("DELETE FROM poi_content").update();

		repository.failGeneration(KEY, leasedAt, FailedClass.TRANSIENT, "provider timed out");

		assertThat(repository.find(KEY)).isEmpty();
		assertThat(rowCount()).isZero();
	}

	/**
	 * The detail is truncated, not refused: losing the record that a POI failed is worse than losing
	 * the tail of a provider message. Measured before the bound existed: a 2 MB detail went into the
	 * row in full, from where it reaches every log and every dump — which is also why the port
	 * forbids building this string out of a request URI.
	 */
	@Test
	void anOverlongFailedDetailIsTruncatedRatherThanRefused() {
		Instant leasedAt = lease();

		repository.failGeneration(KEY, leasedAt, FailedClass.PERMANENT, "detail ".repeat(300_000));

		assertThat(status()).isEqualTo("FAILED");
		assertThat(jdbc.sql(select("length(failed_detail)")).query(Integer.class).single())
				.isEqualTo(ContentRepository.MAX_FAILED_DETAIL_LENGTH);
	}

	// --- the read ---------------------------------------------------------------------------

	@Test
	void aKeyThatWasNeverGeneratedReadsAsAbsent() {
		assertThat(repository.find(KEY)).isEmpty();
	}

	@Test
	void aLeasedRowReadsAsProcessingWithNothingElse() {
		lease();

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.PROCESSING, null, null));
	}

	/** The read is scoped to one key: mode and lang are dimensions of it, not decoration. */
	@Test
	void theReadDoesNotCrossModeOrLanguage() {
		Instant leasedAt = lease();
		repository.completeGeneration(KEY, leasedAt, "the polish text guide", "v1");

		assertThat(repository.find(new PoiContentKey("node/42", ContentMode.AUDIO, "pl"))).isEmpty();
		assertThat(repository.find(new PoiContentKey("node/42", ContentMode.TEXT, "en"))).isEmpty();
		assertThat(repository.find(new PoiContentKey("node/43", ContentMode.TEXT, "pl"))).isEmpty();
		assertThat(repository.find(KEY)).isPresent();
	}

	/** An osm_id carries its element type, so the key is text and the slash is part of it. */
	@Test
	void anOsmIdWithItsElementTypeRoundTrips() {
		PoiContentKey way = new PoiContentKey("way/1234567", ContentMode.TEXT, "pl");
		Instant leasedAt = repository.leaseGeneration(way).leasedAt();
		repository.completeGeneration(way, leasedAt, "a guide to a way", "v1");

		Optional<PoiContent> found = repository.find(way);

		assertThat(found).contains(new PoiContent(ContentStatus.COMPLETED, "a guide to a way", null));
	}

	// --- driving the row into the shapes above ----------------------------------------------

	private Instant lease() {
		return repository.leaseGeneration(KEY).leasedAt();
	}

	/** Backdates the lease past GENERATION_LEASE_TIMEOUT, the way a worker that died would leave it. */
	private void expireTheLease() {
		jdbc.sql("UPDATE poi_content SET started_at = now() - interval '6 minutes'").update();
	}

	/**
	 * The regeneration shape: a COMPLETED row re-leased while its earlier text stays servable.
	 * Written by hand because the lease statement refuses a COMPLETED row today — M5 is what makes
	 * this reachable through the port, and the fencing arms it exercises have to exist before then.
	 */
	private Instant regenerateUnderANewLease() {
		jdbc.sql("""
				UPDATE poi_content
				SET status = 'PROCESSING', started_at = now(), completed_at = NULL, attempts = attempts + 1
				""").update();
		return startedAt();
	}

	// --- reading the row directly -----------------------------------------------------------

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
