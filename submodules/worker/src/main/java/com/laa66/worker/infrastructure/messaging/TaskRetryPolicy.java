package com.laa66.worker.infrastructure.messaging;

import java.time.Duration;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.core.retry.RetryPolicy;

import com.laa66.worker.domain.model.PermanentTaskException;
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
 * <h2>Worst case against content's lease (review M4-08a, LOW-3)</h2>
 *
 * <p>Content takes the lease before it publishes, so the lease clock is already running when the
 * message enters the queue. Assumed: {@code PROVIDER_TIMEOUT = 20 s} (M4-10's accept; not in code
 * yet) and the M4-11 calls around it (pre-check, POI detail, write-back) negligible next to it. One delivery that fails transiently
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
 * {@code PERMANENT}/{@code QUOTA} from this worker misses the fence.
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
	 * Retries per {@link #retryPolicy()}; an exhausted or final failure is rejected without requeue.
	 *
	 * <p><strong>The recoverer is load-bearing.</strong> Without one, Spring AMQP's stateless
	 * interceptor logs "Message(s) dropped on recovery" and the delivery is ACKED: the task is gone,
	 * not dead-lettered, and the container's {@code defaultRequeueRejected=false} never sees an
	 * exception. Measured by mutation: removing this line keeps every task out of the DLQ.
	 */
	public static MethodInterceptor interceptor() {
		return RetryInterceptorBuilder.stateless()
				.retryPolicy(retryPolicy())
				.recoverer(new RejectAndDontRequeueRecoverer())
				.build();
	}

	/**
	 * The listener container wraps whatever the listener throws, so the classification walks the
	 * cause chain. The first classified failure decides: a permanent failure that carries a
	 * transient one as its cause is permanent.
	 */
	static boolean isTransient(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof TransientTaskException) {
				return true;
			}
			if (cause instanceof PermanentTaskException) {
				return false;
			}
		}
		return false;
	}
}
