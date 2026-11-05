package com.laa66.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.support.AbstractPostgresIntegrationTest;

/**
 * Giving back a lease whose task the broker did not confirm: the lease ends, its token dies, and the
 * attempt it counted comes back — but only to the lease that took it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PoiContentReleaseIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");

	@Autowired
	private ContentRepository repository;

	@Test
	void aReleasedLeaseIsATransientFailureWithItsAttemptRefunded() {
		Instant token = grantedLease();

		repository.releaseUnpublishedLease(KEY, token);

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.FAILED, null, FailedClass.TRANSIENT));
		assertThat(row()).isEqualTo("0 true");
	}

	/** The broker-down loop at the row level: any number of lease-and-release cycles costs nothing. */
	@Test
	void releasesNeverSpendTheBudget() {
		for (int click = 0; click < 3 * ContentRepository.MAX_ATTEMPTS_PER_ROW; click++) {
			GenerationLease lease = repository.leaseGeneration(KEY);
			assertThat(lease.outcome()).as("click %d", click).isIn(LeaseOutcome.GRANTED, LeaseOutcome.RETRY_GRANTED);
			repository.releaseUnpublishedLease(KEY, lease.leasedAt());
		}

		assertThat(row()).isEqualTo("0 true");
	}

	@Test
	void theReleasedTokenIsDeadForEveryWrite() {
		Instant token = grantedLease();
		repository.releaseUnpublishedLease(KEY, token);

		repository.failGeneration(KEY, token, FailedClass.PERMANENT, "late worker");
		repository.releaseUnpublishedLease(KEY, token);

		assertThat(repository.find(KEY)).contains(new PoiContent(ContentStatus.FAILED, null, FailedClass.TRANSIENT));
		assertThat(row()).as("a second release refunds nothing").isEqualTo("0 true");
	}

	/** A stale token can neither end the live lease nor refund an attempt somebody else spent. */
	@Test
	void aStaleTokenReleasesNothingAndRefundsNothing() {
		Instant stale = grantedLease();
		jdbc.sql("UPDATE poi_content SET started_at = now() - interval '6 minutes'").update();
		assertThat(repository.leaseGeneration(KEY).outcome()).isEqualTo(LeaseOutcome.GRANTED);

		repository.releaseUnpublishedLease(KEY, stale);

		assertThat(repository.find(KEY).orElseThrow().status()).isEqualTo(ContentStatus.PROCESSING);
		assertThat(jdbc.sql("SELECT attempts FROM poi_content").query(Integer.class).single()).isEqualTo(2);
	}

	/** Paid-for text from an earlier success is not the release's to destroy. */
	@Test
	void aReleaseKeepsTextFromAnEarlierSuccess() {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, text, attempts, failed_class)
				VALUES ('node/42', 'TEXT', 'pl', 'FAILED', 'stara treść', 1, 'QUOTA')
				""").update();
		GenerationLease lease = repository.leaseGeneration(KEY);
		assertThat(lease.outcome()).isEqualTo(LeaseOutcome.RETRY_GRANTED);

		repository.releaseUnpublishedLease(KEY, lease.leasedAt());

		assertThat(repository.find(KEY)).contains(
				new PoiContent(ContentStatus.FAILED, "stara treść", FailedClass.TRANSIENT));
		assertThat(row()).isEqualTo("1 true");
	}

	private Instant grantedLease() {
		GenerationLease lease = repository.leaseGeneration(KEY);
		assertThat(lease.outcome()).isEqualTo(LeaseOutcome.GRANTED);
		return lease.leasedAt();
	}

	private String row() {
		return jdbc.sql("SELECT attempts || ' ' || (started_at IS NULL) FROM poi_content").query(String.class).single();
	}
}
