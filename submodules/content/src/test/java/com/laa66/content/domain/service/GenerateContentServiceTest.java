package com.laa66.content.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.GenerationStatus;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.model.UnsupportedContentModeException;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * The ordering between the three outbound ports, which no HTTP response can show: what each step
 * may only do after the one before it, and what it may never do at all.
 */
class GenerateContentServiceTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");
	private static final String POI = "node/42";
	private static final PoiContentKey KEY = new PoiContentKey(POI, ContentMode.TEXT, "pl");
	private static final Instant TOKEN = Instant.parse("2026-11-02T12:00:00.123456Z");

	private final PoiUnlocks poiUnlocks = mock(PoiUnlocks.class);
	private final ContentRepository repository = mock(ContentRepository.class);
	private final EventPublisher publisher = mock(EventPublisher.class);
	private final GenerateContentService service = new GenerateContentService(poiUnlocks, repository, publisher);

	private GenerationStatus generateWith(LeaseOutcome outcome) {
		boolean granted = outcome == LeaseOutcome.GRANTED || outcome == LeaseOutcome.RETRY_GRANTED;
		return generateWith(new GenerationLease(outcome, granted ? TOKEN : null, null));
	}

	private GenerationStatus generateWith(GenerationLease lease) {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(true);
		when(repository.leaseGeneration(KEY)).thenReturn(lease);
		return service.generate(USER, POI, ContentMode.TEXT);
	}

	@ParameterizedTest
	@EnumSource(value = LeaseOutcome.class, names = { "GRANTED", "RETRY_GRANTED" })
	void aGrantedLeaseIsPublishedWithItsTokenAfterTheGateAndTheLease(LeaseOutcome outcome) {
		assertThat(generateWith(outcome)).isEqualTo(GenerationStatus.processing());

		InOrder order = inOrder(poiUnlocks, repository, publisher);
		order.verify(poiUnlocks).isUnlocked(USER, POI);
		order.verify(repository).leaseGeneration(KEY);
		order.verify(publisher).publish(new GenerateContentTask(KEY, TOKEN));
		verifyNoMoreInteractions(repository, publisher);
	}

	@Test
	void aLeaseHeldByAnotherAttemptIsProcessingWithoutAPublish() {
		assertThat(generateWith(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT)).isEqualTo(GenerationStatus.processing());

		verifyNoInteractions(publisher);
	}

	@Test
	void completedContentIsCompletedWithoutAPublish() {
		assertThat(generateWith(LeaseOutcome.ALREADY_COMPLETED)).isEqualTo(GenerationStatus.completed());

		verifyNoInteractions(publisher);
	}

	/** The class the lease statement read off the row, in the same shape a read of the row gives. */
	@ParameterizedTest
	@EnumSource(FailedClass.class)
	void aRowOutOfBudgetIsFailedWithTheRowsClassAndWithoutAPublish(FailedClass failedClass) {
		assertThat(generateWith(new GenerationLease(LeaseOutcome.ATTEMPTS_EXHAUSTED, null, failedClass)))
				.isEqualTo(GenerationStatus.failed(failedClass));

		verifyNoInteractions(publisher);
	}

	@ParameterizedTest
	@EnumSource(value = ContentMode.class, names = { "AUDIO", "BOTH" })
	void aModeThatIsNotServedAsksNobody(ContentMode mode) {
		assertThatThrownBy(() -> service.generate(USER, POI, mode)).isInstanceOf(UnsupportedContentModeException.class);

		verifyNoInteractions(poiUnlocks, repository, publisher);
	}

	@Test
	void aLockedPoiNeverTouchesTheTableOrTheBroker() {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(false);

		assertThatThrownBy(() -> service.generate(USER, POI, ContentMode.TEXT)).isInstanceOf(PoiNotUnlockedException.class);

		verifyNoInteractions(repository, publisher);
	}

	@Test
	void anUnknownUnlockStateNeverTouchesTheTableOrTheBroker() {
		when(poiUnlocks.isUnlocked(USER, POI)).thenThrow(new PoiUnlockStateUnavailableException("down"));

		assertThatThrownBy(() -> service.generate(USER, POI, ContentMode.TEXT))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		verifyNoInteractions(repository, publisher);
	}

	@Test
	void aFailedPublishReleasesTheLeaseItWasIssuedUnderAndStillFails() {
		EventPublicationException failure = new EventPublicationException("no broker confirm");
		doThrow(failure).when(publisher).publish(any());

		assertThatThrownBy(() -> generateWith(LeaseOutcome.GRANTED)).isSameAs(failure);

		InOrder order = inOrder(repository, publisher);
		order.verify(repository).leaseGeneration(KEY);
		order.verify(publisher).publish(new GenerateContentTask(KEY, TOKEN));
		order.verify(repository).releaseUnpublishedLease(KEY, TOKEN);
	}

	/** The row then expires into a takeover; the client still has to hear about the publish. */
	@Test
	void aReleaseThatFailsDoesNotHideThePublicationFailure() {
		EventPublicationException failure = new EventPublicationException("no broker confirm");
		IllegalStateException releaseFailure = new IllegalStateException("database down");
		doThrow(failure).when(publisher).publish(any());
		doThrow(releaseFailure).when(repository).releaseUnpublishedLease(KEY, TOKEN);

		assertThatThrownBy(() -> generateWith(LeaseOutcome.GRANTED)).isSameAs(failure)
				.hasSuppressedException(releaseFailure);
	}

	/** Anything else from the publisher is a bug, not a broker verdict, and is not answered with a release. */
	@Test
	void aBugInThePublisherIsNotTreatedAsABrokerFailure() {
		doThrow(new IllegalStateException("bug")).when(publisher).publish(any());

		assertThatThrownBy(() -> generateWith(LeaseOutcome.GRANTED)).isInstanceOf(IllegalStateException.class);

		verify(repository, never()).releaseUnpublishedLease(any(), any());
	}
}
