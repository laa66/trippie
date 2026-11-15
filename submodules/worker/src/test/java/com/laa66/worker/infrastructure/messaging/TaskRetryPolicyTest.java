package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * The policy's numbers, read off the object the container runs. The counting itself, on a live
 * broker, is in {@code GenerationTaskPipelineIntegrationTest}.
 */
class TaskRetryPolicyTest {

	private static final Message MESSAGE = MessageBuilder
			.withBody(GenerationTaskDecodeTest.CONTENT_BODY.getBytes(StandardCharsets.UTF_8)).build();

	private final List<Map.Entry<GenerateContentTask, Throwable>> givenUp = new ArrayList<>();

	private final List<ConsumerMustStopException> reported = new ArrayList<>();

	private final MessageRecoverer recoverer = TaskRetryPolicy.recoverer(
			(task, failure) -> givenUp.add(Map.entry(task, failure)), reported::add);

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
		ProviderCredentialRejectedException rejected = new ProviderCredentialRejectedException("gemini: HTTP 401");

		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE, wrapped(rejected)));

		assertThat(reported).containsExactly(rejected);
		assertThat(givenUp).isEmpty();
		assertThat(requeued(thrown)).isTrue();
	}

	/** Review M4-11, LOW-3: content or spatial misconfigured takes the credential's path. */
	@Test
	void aMisconfiguredServiceIsNotRetriedButRequeuedAndReported() {
		ConsumerMustStopException stop = new ConsumerMustStopException("content answered HTTP 404 to the lease check", "fix it");

		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE, wrapped(new PermanentTaskException("x", stop))));

		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(new TransientTaskException("x", stop)))).isFalse();
		assertThat(reported).containsExactly(stop);
		assertThat(givenUp).isEmpty();
		assertThat(requeued(thrown)).isTrue();
	}

	/** Review M4-11 round 2, LOW-2: the failure's own write-back finds content misconfigured. */
	@Test
	void aStopMetWhileGivingUpIsRequeuedNotDeadLetteredAndReported() {
		ConsumerMustStopException stop = new ConsumerMustStopException("content answered HTTP 404 to the failure", "fix it");
		MessageRecoverer stopping = TaskRetryPolicy.recoverer((task, failure) -> {
			throw stop;
		}, reported::add);

		Throwable thrown = catchThrowable(() -> stopping.recover(MESSAGE, wrapped(new PermanentTaskException("x"))));

		assertThat(reported).containsExactly(stop);
		assertThat(requeued(thrown)).isTrue();
	}

	@Test
	void anyOtherFinalFailureIsGivenUpThenDeadLettered() {
		ListenerExecutionFailedException failure = wrapped(new TextGenerationException(FailedClass.PERMANENT, "gemini: HTTP 404"));

		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE, failure));

		assertThat(reported).isEmpty();
		assertThat(givenUp).containsExactly(Map.entry(GenerationTaskListener.decode(MESSAGE.getBody()), failure));
		assertThat(requeued(thrown)).isFalse();
	}

	@Test
	void anUndecodableBodyIsDeadLetteredWithNothingToGiveUp() {
		Message garbage = MessageBuilder.withBody("{".getBytes(StandardCharsets.UTF_8)).build();

		Throwable thrown = catchThrowable(() -> recoverer.recover(garbage,
				wrapped(new PermanentTaskException("undecodable generation task"))));

		assertThat(givenUp).isEmpty();
		assertThat(requeued(thrown)).isFalse();
	}

	/**
	 * Review M4-09 LOW-1 / M4-10: the backoff interrupted by a shutdown ends in the recoverer with the
	 * interrupt flag set and the last (transient) failure as the cause. Not a failure of the POI.
	 */
	@Test
	void aDeliveryCutShortByAnInterruptIsRequeuedAndNotGivenUp() {
		Thread.currentThread().interrupt();
		Throwable thrown;
		try {
			thrown = catchThrowable(() -> recoverer.recover(MESSAGE,
					wrapped(new TextGenerationException(FailedClass.TRANSIENT, "gemini: interrupted"))));
		}
		finally {
			Thread.interrupted();
		}

		assertThat(givenUp).isEmpty();
		assertThat(requeued(thrown)).isTrue();
	}

	@Test
	void anAttemptRefusedBecauseTheContainerIsStoppingIsRequeuedAndNotGivenUp() {
		Throwable thrown = catchThrowable(() -> recoverer.recover(MESSAGE, wrapped(new ConsumerStoppingException())));

		assertThat(givenUp).isEmpty();
		assertThat(requeued(thrown)).isTrue();
	}

	@Test
	void aStoppingContainerIsNotRetried() {
		assertThat(TaskRetryPolicy.retryPolicy().shouldRetry(wrapped(new ConsumerStoppingException()))).isFalse();
	}

	/** Read through the container's own decision, with this worker's {@code defaultRequeueRejected=false}. */
	private boolean requeued(Throwable thrown) {
		return ContainerUtils.shouldRequeue(false, thrown, LogFactory.getLog(getClass()));
	}

	private static ListenerExecutionFailedException wrapped(Throwable cause) {
		return new ListenerExecutionFailedException("Listener threw exception", cause);
	}
}
