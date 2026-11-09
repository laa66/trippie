package com.laa66.worker.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.support.ManualClock;

class PacedTextGeneratorTest {

	private static final PoiPrompt PROMPT = new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl");

	private final ManualClock clock = new ManualClock(0);

	private final AtomicInteger calls = new AtomicInteger();

	@Test
	void everyCallWaitsForItsSlotAndReturnsTheProvidersText() {
		PacedTextGenerator generator = new PacedTextGenerator(new ProviderCallPacer(clock, clock.sleeper()), prompt -> {
			calls.incrementAndGet();
			return new GeneratedText("Tekst o " + prompt.name(), "v");
		});

		assertThat(generator.generate(PROMPT).text()).isEqualTo("Tekst o Ratusz");
		generator.generate(PROMPT);

		assertThat(calls).hasValue(2);
		assertThat(clock.sleeps()).containsExactly(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING);
	}

	@Test
	void aFailedProviderCallPassesThroughUnchangedAndStillTakesItsSlot() {
		TextGenerationException quota = new TextGenerationException(FailedClass.QUOTA, "daily quota");
		PacedTextGenerator generator = new PacedTextGenerator(new ProviderCallPacer(clock, clock.sleeper()), prompt -> {
			calls.incrementAndGet();
			throw quota;
		});

		assertThatThrownBy(() -> generator.generate(PROMPT)).isSameAs(quota);
		assertThatThrownBy(() -> generator.generate(PROMPT)).isSameAs(quota);

		assertThat(clock.sleeps()).containsExactly(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING);
	}

	/**
	 * Review M4-10: an interrupt while waiting for the slot is TRANSIENT like an interrupted provider
	 * call, keeps the flag, and the provider is never called. On the production ticker and sleeper.
	 */
	@Test
	@Timeout(10)
	void anInterruptWhileWaitingForTheSlotIsTransientAndTheInterruptIsKept() throws Exception {
		PacedTextGenerator generator = new PacedTextGenerator(new ProviderCallPacer(System::nanoTime, Thread::sleep),
				prompt -> {
					calls.incrementAndGet();
					return new GeneratedText("Tekst.", "v");
				});
		generator.generate(PROMPT);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().unstarted(() -> {
			try {
				generator.generate(PROMPT);
			}
			catch (Throwable ex) {
				thrown.set(ex);
			}
			interruptedAfter.set(Thread.currentThread().isInterrupted());
		});

		caller.start();
		awaitSleeping(caller);
		caller.interrupt();
		caller.join(5_000);

		assertThat(thrown.get()).isInstanceOf(TextGenerationException.class);
		assertThat(((TextGenerationException) thrown.get()).failedClass()).isEqualTo(FailedClass.TRANSIENT);
		assertThat(interruptedAfter.get()).isTrue();
		assertThat(calls).hasValue(1);
	}

	private static void awaitSleeping(Thread thread) throws InterruptedException {
		while (thread.getState() != Thread.State.TIMED_WAITING) {
			Thread.sleep(10);
		}
	}
}
