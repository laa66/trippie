package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOffExecution;

import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * The policy's numbers, read off the object the container runs. The counting itself, on a live
 * broker, is in {@code GenerationTaskPipelineIntegrationTest}.
 */
class TaskRetryPolicyTest {

	@Test
	void threeAttemptsWithFiveThenFifteenSecondsBetweenThem() {
		BackOffExecution backOff = TaskRetryPolicy.retryPolicy().getBackOff().start();

		assertThat(backOff.nextBackOff()).isEqualTo(5_000);
		assertThat(backOff.nextBackOff()).isEqualTo(15_000);
		assertThat(backOff.nextBackOff()).isEqualTo(BackOffExecution.STOP);
	}

	@Test
	void theFirstBackoffIsNotShorterThanTheProviderCallSpacing() {
		assertThat(TaskRetryPolicy.FIRST_RETRY_DELAY)
				.isGreaterThanOrEqualTo(com.laa66.worker.domain.service.ProviderCallPacer.MIN_PROVIDER_CALL_SPACING);
	}

	@Test
	void aTransientFailureIsRetriedThroughTheContainersWrapper() {
		RetryPolicy policy = TaskRetryPolicy.retryPolicy();

		assertThat(policy.shouldRetry(wrapped(new TransientTaskException("provider 503")))).isTrue();
	}

	@Test
	void aPermanentFailureIsNotRetried() {
		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(new PermanentTaskException("400")))).isFalse();
	}

	@Test
	void anUnclassifiedFailureIsNotRetried() {
		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(new IllegalStateException("bug")))).isFalse();
	}

	@Test
	void aPermanentFailureCausedByATransientOneIsPermanent() {
		PermanentTaskException failure = new PermanentTaskException("400", new TransientTaskException("503"));

		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(failure))).isFalse();
	}

	private static ListenerExecutionFailedException wrapped(Throwable cause) {
		return new ListenerExecutionFailedException("Listener threw exception", cause);
	}
}
