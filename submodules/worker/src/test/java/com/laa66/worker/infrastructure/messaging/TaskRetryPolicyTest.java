package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.listener.support.ContainerUtils;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOffExecution;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * The policy's numbers, read off the object the container runs. The counting itself, on a live
 * broker, is in {@code GenerationTaskPipelineIntegrationTest}.
 */
class TaskRetryPolicyTest {

	private static final Message MESSAGE = MessageBuilder.withBody(new byte[0]).build();

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

	/** A spent daily quota does not come back within the backoff, so it costs one attempt. */
	@ParameterizedTest
	@CsvSource({ "TRANSIENT, true", "PERMANENT, false", "QUOTA, false" })
	void aProviderFailureIsRetriedOnlyWhenTransient(FailedClass failedClass, boolean retried) {
		TextGenerationException failure = new TextGenerationException(failedClass, "gemini");

		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(failure))).isEqualTo(retried);
	}

	/** Not even when something around it claims to be transient: the key is wrong for every task. */
	@Test
	void aRejectedCredentialIsNotRetried() {
		ProviderCredentialRejectedException rejected = new ProviderCredentialRejectedException("gemini: HTTP 401");

		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(rejected))).isFalse();
		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(new TransientTaskException("x", rejected)))).isFalse();
	}

	/**
	 * Read through {@link ContainerUtils#shouldRequeue}, the container's own decision, with this
	 * worker's {@code defaultRequeueRejected=false}.
	 */
	@Test
	void aRejectedCredentialIsRequeuedNotDeadLetteredAndReported() {
		List<ProviderCredentialRejectedException> reported = new ArrayList<>();
		MessageRecoverer recoverer = TaskRetryPolicy.recoverer(reported::add);
		ProviderCredentialRejectedException rejected = new ProviderCredentialRejectedException("gemini: HTTP 401");

		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE, wrapped(rejected)));

		assertThat(reported).containsExactly(rejected);
		assertThat(ContainerUtils.shouldRequeue(false, thrown, LogFactory.getLog(getClass()))).isTrue();
	}

	@Test
	void anyOtherFinalFailureIsDeadLetteredAndNotReported() {
		List<ProviderCredentialRejectedException> reported = new ArrayList<>();
		MessageRecoverer recoverer = TaskRetryPolicy.recoverer(reported::add);

		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE,
				wrapped(new TextGenerationException(FailedClass.PERMANENT, "gemini: HTTP 404"))));

		assertThat(reported).isEmpty();
		assertThat(ContainerUtils.shouldRequeue(false, thrown, LogFactory.getLog(getClass()))).isFalse();
	}

	private static ListenerExecutionFailedException wrapped(Throwable cause) {
		return new ListenerExecutionFailedException("Listener threw exception", cause);
	}
}
