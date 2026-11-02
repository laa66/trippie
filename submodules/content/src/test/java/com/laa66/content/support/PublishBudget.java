package com.laa66.content.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.assertj.core.api.ThrowableAssert;

import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.infrastructure.config.MessagingConfig;

/**
 * The time bound {@link EventPublisher} promises, as one number every broker-failure test measures
 * each publish against, one by one: a sum over several publishes would let one of them take the
 * others' share.
 *
 * <p>The slowest path measured is the confirm wait, so the budget is that plus slack for a loaded
 * machine. Every other step is bounded below it: connect by {@code connection-timeout}, every
 * channel RPC by {@code channel-rpc-timeout}, the handshake by the client's own timeout (measured
 * 5 s against a frozen broker).
 */
public final class PublishBudget {

	public static final Duration PER_PUBLISH = MessagingConfig.CONFIRM_TIMEOUT.plusSeconds(2);

	private PublishBudget() {
	}

	/** Preemptive, so a regression costs the budget and not the client's 10 min. */
	public static EventPublicationException assertFailsWithinBudget(EventPublisher publisher, GenerateContentTask task) {
		return assertTimeoutPreemptively(PER_PUBLISH, () -> {
			Throwable thrown = ThrowableAssert.catchThrowable(() -> publisher.publish(task));
			assertThat(thrown).as("a publish the broker did not take must throw").isInstanceOf(EventPublicationException.class);
			return (EventPublicationException) thrown;
		}, () -> "a publish took longer than " + PER_PUBLISH);
	}
}
