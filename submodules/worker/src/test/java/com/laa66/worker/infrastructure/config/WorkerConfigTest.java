package com.laa66.worker.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.laa66.worker.domain.service.ProviderCallPacer;

class WorkerConfigTest {

	/**
	 * A wall-clock source (epoch nanos from a {@code Clock}) would pace correctly until NTP or a
	 * resumed WSL2 VM steps it back, then hold every provider call for the length of the step. A
	 * reading bracketed by two {@code System.nanoTime} calls is from the monotonic clock: epoch nanos
	 * are about 1.7e18 and fall outside the bracket.
	 */
	@Test
	void thePacerTicksOnTheMonotonicClockNotTheWallClock() {
		ProviderCallPacer.Ticker ticker = new WorkerConfig().ticker();

		long before = System.nanoTime();
		long reading = ticker.nanoTime();
		long after = System.nanoTime();

		assertThat(reading - before).isNotNegative();
		assertThat(after - reading).isNotNegative();
	}
}
