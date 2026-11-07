package com.laa66.worker.domain.service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Keeps the starts of provider calls at least {@link #MIN_PROVIDER_CALL_SPACING} apart.
 *
 * <p>It wraps the provider call and nothing else. Paths that settle a task without the provider (the
 * M4-11 pre-check finding the row already {@code COMPLETED} or the token dead) must not pass through
 * here: throttling them would hold the queue behind calls that cost nothing.
 *
 * <p>The start is taken after the wait, from the same ticker, so a late wake-up pushes the next slot
 * back instead of eating into it. The ticker is monotonic ({@code System.nanoTime} in production),
 * never the wall clock: a wall clock stepped back by NTP or a resumed WSL2 VM would hold every
 * provider call for the length of the step. Its readings are compared only as differences, since
 * the origin is arbitrary and may be negative or wrap.
 *
 * <p>The wait holds a lock and the call does not: two callers can never take the same slot, and a
 * slow call does not delay the next one beyond the spacing. A {@link ReentrantLock} and not
 * {@code synchronized}, which on JDK 21 pins the virtual thread's carrier for the whole sleep.
 */
public class ProviderCallPacer {

	/**
	 * 60 s / 15 RPM, the provider's free-tier rate. A constant for the reason
	 * {@code ContentRepository.GENERATION_LEASE_TIMEOUT} gives: relaxed binding would let an env
	 * var turn it into 4 ms.
	 */
	public static final Duration MIN_PROVIDER_CALL_SPACING = Duration.ofSeconds(4);

	private static final long SPACING_NANOS = MIN_PROVIDER_CALL_SPACING.toNanos();

	/** A monotonic nanosecond source with an arbitrary origin. */
	@FunctionalInterface
	public interface Ticker {

		long nanoTime();
	}

	@FunctionalInterface
	public interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	private final Ticker ticker;

	private final Sleeper sleeper;

	private final ReentrantLock lock = new ReentrantLock();

	private boolean started;

	private long lastStart;

	public ProviderCallPacer(Ticker ticker, Sleeper sleeper) {
		this.ticker = Objects.requireNonNull(ticker, "ticker");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
	}

	public <T> T call(Supplier<T> providerCall) {
		awaitSlot();
		return providerCall.get();
	}

	private void awaitSlot() {
		lock.lock();
		try {
			if (started) {
				for (long left = remaining(); left > 0; left = remaining()) {
					sleeper.sleep(Duration.ofNanos(left));
				}
			}
			lastStart = ticker.nanoTime();
			started = true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted waiting for the provider call slot", ex);
		}
		finally {
			lock.unlock();
		}
	}

	private long remaining() {
		return SPACING_NANOS - (ticker.nanoTime() - lastStart);
	}
}
