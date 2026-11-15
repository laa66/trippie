package com.laa66.worker.infrastructure.messaging;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.core.retry.RetryPolicy;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * How many times one delivery is attempted, and when. Every number is derived from the provider's
 * free tier (15 RPM, 500 RPD), not picked.
 *
 * <p><strong>Attempts are counted here, in-process, never by redelivery.</strong> A classic queue
 * without {@code x-delivery-limit} does not count deliveries, and {@code nack(requeue=true)} hands
 * the message straight back to the same consumer: a hot loop over a paid API. So the retries run
 * inside one delivery, blocking the (single) consumer through the backoff, and an exhausted delivery
 * is rejected without requeue, which the queue's dead-letter exchange routes into the DLQ.
 *
 * <p><strong>Only a {@link TransientTaskException} is retried.</strong> Anything else, including an
 * exception nobody classified, costs exactly one attempt: a poisoned POI must cost one provider
 * request, not three, and that is the largest saving of quota in the whole policy.
 *
 * <p><strong>A {@link ConsumerMustStopException} is neither retried nor dead-lettered.</strong> A
 * refused provider key, or content or spatial answering as if the worker were pointed at the wrong
 * service: not about the task. The consumer is stopped and the task goes back to the queue (see
 * {@link #recoverer}).
 *
 * <p><strong>Neither is a delivery the shutdown cut short</strong> (review M4-09, LOW-1): an
 * interrupt in the backoff, in the pacer's wait or in a blocking call, or an attempt refused because
 * the container is stopping. Nothing about the POI is known, so it is not recorded as a failure, and
 * the task goes back to the queue. No paid attempt follows an interrupt: the backoff after it ends at
 * once, and the policy gives up.
 *
 * <h2>Worst case against content's lease (review M4-08a, LOW-3)</h2>
 *
 * <p>Content takes the lease before it publishes, so the lease clock is already running when the
 * message enters the queue. Assumed: {@code GeminiTextGenerator.PROVIDER_TIMEOUT} (20 s), with the
 * calls around it (pre-check, POI detail, write-back) answering in milliseconds; each of them can
 * add up to {@code WorkerConfig.SERVICE_TIMEOUT} when content or spatial stalls. One delivery that fails transiently
 * every time, from the moment the consumer takes it to the end of its last provider attempt:
 *
 * <pre>
 *   pacer  4 s  (at most one spacing behind the previous task's call)
 * + 20 s  attempt 1
 * +  5 s  backoff (no pacer wait: 25 s since the last start)
 * + 20 s  attempt 2
 * + 15 s  backoff
 * + 20 s  attempt 3
 * = 84 s
 * </pre>
 *
 * <p>The consumer is serial, so a task with N tasks ahead of it waits for them first:
 * at most {@code (N + 1) * 84 s} (exactly {@code 4 s + (N + 1) * 80 s}: only the first of them can
 * wait for the pacer, every later start is 20 s past the previous one). Against
 * {@code GENERATION_LEASE_TIMEOUT = 300 s}, N = 2 finishes by 252 s; <strong>N = 3 finishes at
 * 324-336 s, past the lease.</strong> This needs a provider outage (5xx or timeouts on every call)
 * with four tasks queued inside it. When the provider answers, each task holds the consumer for max(4 s pacer, provider latency):
 * the lease is exceeded behind roughly 60-75 queued tasks at a 4-5 s latency, but behind only ~15
 * when every call succeeds just under the 20 s timeout. Not counted above: content's publish (up to
 * its 5 s confirm wait) runs inside the lease too, before the message is in the queue.
 *
 * <p>Below the lease nothing is lost. Past it, the row with budget left is taken over by the next
 * POST (a second message, and possibly a second paid call while the first is still running), and
 * the row whose budget is spent is parked as {@code FAILED/TRANSIENT}, so a late
 * {@code PERMANENT}/{@code QUOTA} from this worker misses the fence. What the lease bounds is the
 * spending: the pre-check runs before every attempt (M4-11), so no attempt of a delivery whose lease
 * has expired calls the provider; only an attempt already past its pre-check when the lease runs out
 * can. <strong>A text already paid for is lost when its write-back did not land and the lease is
 * no longer live at the next attempt's pre-check</strong> (expired or taken over): the delivery is
 * acknowledged without writing the kept text, and the next POST pays for the POI again. Content's
 * completion fence refuses a late text only when the row already carries one, so that refusal loses
 * nothing servable. Accepted by the user (review M4-11, LOW-1, 2026-11-12).
 * {@code GENERATION_LEASE_TIMEOUT} stays at 5 min (user decision 2026-11-07).
 */
public final class TaskRetryPolicy {

	/** One attempt plus two retries. */
	public static final int MAX_ATTEMPTS_PER_DELIVERY = 3;

	/**
	 * At least {@code ProviderCallPacer.MIN_PROVIDER_CALL_SPACING} (4 s), so a retry never has to
	 * wait for the pacer on top of the backoff and never breaks the RPM limit by itself.
	 */
	public static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(5);

	/** 5 s, then 15 s. */
	public static final double RETRY_DELAY_MULTIPLIER = 3;

	public static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(15);

	private TaskRetryPolicy() {
	}

	public static RetryPolicy retryPolicy() {
		return RetryPolicy.builder()
				.maxRetries(MAX_ATTEMPTS_PER_DELIVERY - 1)
				.delay(FIRST_RETRY_DELAY)
				.multiplier(RETRY_DELAY_MULTIPLIER)
				.maxDelay(MAX_RETRY_DELAY)
				.predicate(TaskRetryPolicy::isTransient)
				.build();
	}

	/**
	 * Retries per {@link #retryPolicy()}, then hands the last failure to {@link #recoverer}.
	 *
	 * <p><strong>The recoverer is load-bearing.</strong> Without one, Spring AMQP's stateless
	 * interceptor logs "Message(s) dropped on recovery" and the delivery is ACKED: the task is gone,
	 * not dead-lettered, and the container's {@code defaultRequeueRejected=false} never sees an
	 * exception. Measured by mutation: removing this line keeps every task out of the DLQ.
	 */
	public static MethodInterceptor interceptor(BiConsumer<GenerateContentTask, Throwable> onGiveUp,
			Consumer<ConsumerMustStopException> onMustStop) {
		return RetryInterceptorBuilder.stateless()
				.retryPolicy(retryPolicy())
				.recoverer(recoverer(onGiveUp, onMustStop))
				.build();
	}

	/**
	 * A {@link ConsumerMustStopException} goes to {@code onMustStop}, then back to the queue, also
	 * when {@code onGiveUp} meets one while recording the failure (review M4-11 round 2, LOW-2). A delivery
	 * the shutdown cut short goes back to the queue unrecorded. Every other exhausted or final failure
	 * goes to {@code onGiveUp} (unless the body does not decode into a task) and is rejected without
	 * requeue, into the DLQ.
	 *
	 * <p>{@code onMustStop} runs on the consumer thread before the requeue, so stopping the
	 * container there cancels the consumer on the broker before the message is handed back: the
	 * broker has nobody to redeliver it to, and nothing is called again. A requeue during
	 * shutdown races the closing channel; either way the broker keeps the message.
	 */
	static MessageRecoverer recoverer(BiConsumer<GenerateContentTask, Throwable> onGiveUp,
			Consumer<ConsumerMustStopException> onMustStop) {
		MessageRecoverer reject = new RejectAndDontRequeueRecoverer();
		return (message, cause) -> {
			ConsumerMustStopException mustStop = mustStop(cause);
			if (mustStop != null) {
				throw stopAndRequeue(mustStop, onMustStop);
			}
			if (Thread.currentThread().isInterrupted() || stoppedBeforeTheAttempt(cause)) {
				throw new ImmediateRequeueAmqpException("delivery cut short by shutdown, the task goes back to the queue", cause);
			}
			GenerateContentTask task = decodable(message.getBody());
			if (task != null) {
				try {
					onGiveUp.accept(task, cause);
				}
				catch (ConsumerMustStopException stop) {
					throw stopAndRequeue(stop, onMustStop);
				}
			}
			reject.recover(message, cause);
		};
	}

	private static ImmediateRequeueAmqpException stopAndRequeue(ConsumerMustStopException stop,
			Consumer<ConsumerMustStopException> onMustStop) {
		onMustStop.accept(stop);
		return new ImmediateRequeueAmqpException("the consumer must stop, the task goes back to the queue", stop);
	}

	private static GenerateContentTask decodable(byte[] body) {
		try {
			return GenerationTaskListener.decode(body);
		}
		catch (PermanentTaskException ex) {
			return null;
		}
	}

	private static boolean stoppedBeforeTheAttempt(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConsumerStoppingException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The listener container wraps whatever the listener throws, so the classification walks the
	 * cause chain. The first classified failure decides: a permanent failure that carries a
	 * transient one as its cause is permanent.
	 *
	 * <p>A provider failure is retried only when it is {@link FailedClass#TRANSIENT}. A spent daily
	 * quota does not come back within the 20 s of backoff, so {@link FailedClass#QUOTA} costs one
	 * attempt, like a permanent failure.
	 */
	static boolean isTransient(Throwable failure) {
		if (mustStop(failure) != null) {
			return false;
		}
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof TransientTaskException) {
				return true;
			}
			if (cause instanceof PermanentTaskException) {
				return false;
			}
			if (cause instanceof TextGenerationException generation) {
				return generation.failedClass() == FailedClass.TRANSIENT;
			}
		}
		return false;
	}

	/** Anywhere in the chain: a stop decides over every classification around it. */
	private static ConsumerMustStopException mustStop(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConsumerMustStopException stop) {
				return stop;
			}
		}
		return null;
	}
}
