package com.laa66.worker.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

	/** Review M4-11, LOW-3: through the bean methods, so the check cannot be dropped from either. */
	@ParameterizedTest
	@ValueSource(strings = { "", "content:8080", "content", "/internal", "//content:8080", "ftp://content:8080",
			"http:content", "http://", "http://content_x:8080", "http://con tent:8080", "file:///etc/passwd" })
	void aServiceUriThatCannotBeAServiceRootStopsTheStartupWithoutEchoingIt(String uri) {
		WorkerConfig config = new WorkerConfig();

		assertThatThrownBy(() -> config.generationResults(uri)).isInstanceOf(IllegalStateException.class)
				.hasMessage("trippie.content.uri must be an absolute http or https URI with a host");
		assertThatThrownBy(() -> config.poiDetails(uri)).isInstanceOf(IllegalStateException.class)
				.hasMessage("trippie.spatial.uri must be an absolute http or https URI with a host");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://content:8080", "https://content.example", "HTTP://127.0.0.1:1", "http://[::1]:8080" })
	void anAbsoluteHttpUriWithAHostIsAServiceRoot(String uri) {
		WorkerConfig config = new WorkerConfig();

		assertThatCode(() -> config.generationResults(uri)).doesNotThrowAnyException();
		assertThatCode(() -> config.poiDetails(uri)).doesNotThrowAnyException();
	}
}
