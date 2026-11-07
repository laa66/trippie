package com.laa66.worker.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.laa66.worker.domain.service.ProviderCallPacer;

/**
 * A monotonic ticker that moves only when told to, plus the sleeper that moves it: sleeping
 * {@code d} advances it by exactly {@code d} and returns at once. Every requested sleep is recorded.
 */
public final class ManualClock implements ProviderCallPacer.Ticker {

	private volatile long nanos;

	private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

	public ManualClock(long startNanos) {
		this.nanos = startNanos;
	}

	public synchronized void advance(Duration duration) {
		nanos += duration.toNanos();
	}

	public ProviderCallPacer.Sleeper sleeper() {
		return duration -> {
			sleeps.add(duration);
			advance(duration);
		};
	}

	public List<Duration> sleeps() {
		return List.copyOf(sleeps);
	}

	@Override
	public long nanoTime() {
		return nanos;
	}
}
