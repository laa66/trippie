package com.laa66.worker.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.laa66.worker.support.ManualClock;

class ProviderCallPacerTest {

	private static final long T0 = 1_000_000_000L;

	private final ManualClock clock = new ManualClock(T0);

	private final ProviderCallPacer pacer = new ProviderCallPacer(clock, clock.sleeper());

	/** Each start as the time since T0. */
	private final List<Duration> starts = new ArrayList<>();

	@Test
	void theSpacingIsFourSecondsWhichIsSixtyOverFifteenRpm() {
		assertThat(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING).isEqualTo(Duration.ofSeconds(60 / 15));
	}

	@Test
	void fiveBackToBackCallsStartExactlyFourSecondsApart() {
		for (int i = 0; i < 5; i++) {
			pacer.call(this::recordStart);
		}

		assertThat(starts).containsExactly(Duration.ZERO, seconds(4), seconds(8), seconds(12), seconds(16));
	}

	@Test
	void theFirstCallDoesNotWait() {
		pacer.call(this::recordStart);

		assertThat(clock.sleeps()).isEmpty();
		assertThat(starts).containsExactly(Duration.ZERO);
	}

	@Test
	void theSpacingRunsFromTheStartSoACallLongerThanItDelaysNothing() {
		pacer.call(() -> {
			recordStart();
			clock.advance(seconds(20));
			return null;
		});
		pacer.call(this::recordStart);

		assertThat(clock.sleeps()).isEmpty();
		assertThat(starts).containsExactly(Duration.ZERO, seconds(20));
	}

	@Test
	void aShortCallWaitsOnlyForTheRestOfTheSpacing() {
		pacer.call(() -> {
			recordStart();
			clock.advance(Duration.ofMillis(1_500));
			return null;
		});
		pacer.call(this::recordStart);

		assertThat(clock.sleeps()).containsExactly(Duration.ofMillis(2_500));
		assertThat(starts.get(1)).isEqualTo(seconds(4));
	}

	@Test
	void aWakeUpBeforeTheSlotSleepsAgainUntilTheSlot() {
		List<Duration> sleeps = new ArrayList<>();
		ProviderCallPacer early = new ProviderCallPacer(clock, duration -> {
			sleeps.add(duration);
			clock.advance(duration.compareTo(seconds(1)) > 0 ? duration.minusSeconds(1) : duration);
		});
		early.call(this::recordStart);
		early.call(this::recordStart);

		assertThat(sleeps).containsExactly(seconds(4), seconds(1));
		assertThat(starts.get(1).minus(starts.get(0))).isEqualTo(seconds(4));
	}

	@Test
	void aFailedCallStillCountsAsAStart() {
		assertThatThrownBy(() -> pacer.call(() -> {
			recordStart();
			throw new IllegalStateException("provider 503");
		})).isInstanceOf(IllegalStateException.class);
		pacer.call(this::recordStart);

		assertThat(starts).containsExactly(Duration.ZERO, seconds(4));
	}

	/**
	 * {@code System.nanoTime} has an arbitrary origin: it may be negative, and it may cross
	 * {@code Long.MAX_VALUE} between two calls. Only the difference of two readings means anything.
	 */
	@ParameterizedTest
	@ValueSource(longs = { Long.MAX_VALUE - 2_000_000_000L, Long.MIN_VALUE, -5_000_000_000L })
	void theSpacingHoldsWhateverTheTickersOrigin(long origin) {
		ManualClock ticker = new ManualClock(origin);
		ProviderCallPacer paced = new ProviderCallPacer(ticker, ticker.sleeper());

		paced.call(() -> null);
		ticker.advance(Duration.ofMillis(1_500));
		paced.call(() -> null);

		assertThat(ticker.sleeps()).containsExactly(Duration.ofMillis(2_500));
	}

	private Object recordStart() {
		starts.add(Duration.ofNanos(clock.nanoTime() - T0));
		return null;
	}

	private static Duration seconds(long seconds) {
		return Duration.ofSeconds(seconds);
	}
}
