package com.laa66.content.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * The value of this decorator is a number, so every test counts calls to the port. Time is a
 * controlled clock, never a sleep.
 */
class CachingPoiUnlocksTest {

	private static final String POI = "node/42";
	private static final String OTHER_POI = "node/43";
	private static final UUID USER = UUID.randomUUID();
	private static final UUID OTHER_USER = UUID.randomUUID();
	private static final Instant START = Instant.parse("2026-10-30T12:00:00Z");
	private static final PoiUnlocks YES = (user, poi) -> true;
	private static final PoiUnlocks NO = (user, poi) -> false;
	private static final PoiUnlocks OUTAGE = (user, poi) -> {
		throw new PoiUnlockStateUnavailableException("spatial is down");
	};

	private UUID eldestUser;
	private UUID secondEldestUser;

	private final AtomicReference<Instant> now = new AtomicReference<>(START);
	private final CountingPort port = new CountingPort();
	private final PoiUnlocks cache = new CachingPoiUnlocks(port, now::get);
	private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});

	@AfterEach
	void stopThreads() {
		threads.shutdownNow();
	}

	/** The tests below scale with the constants, so they cannot see them change. */
	@Test
	void thePolicyIsTenMinutesAndTenThousandEntries() {
		assertThat(CachingPoiUnlocks.TTL).isEqualTo(Duration.ofMinutes(10));
		assertThat(CachingPoiUnlocks.MAX_ENTRIES).isEqualTo(10_000);
	}

	@Test
	void aPositiveReadTwice_asksThePortOnce() {
		port.answers(YES);

		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(cache.isUnlocked(USER, POI)).isTrue();

		assertThat(port.calls()).isEqualTo(1);
	}

	@Test
	void aNegativeReadTwice_asksThePortTwice() {
		port.answers(NO);

		assertThat(cache.isUnlocked(USER, POI)).isFalse();
		assertThat(cache.isUnlocked(USER, POI)).isFalse();

		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void aNegativeThenAPositive_isTrueWithoutWaitingForTheTtl() {
		port.answers(NO);
		assertThat(cache.isUnlocked(USER, POI)).isFalse();

		port.answers(YES);

		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void anOutage_isPropagatedAndNotRemembered() {
		port.answers(OUTAGE);

		assertThatThrownBy(() -> cache.isUnlocked(USER, POI))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);
		assertThatThrownBy(() -> cache.isUnlocked(USER, POI))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void anOutageThenAPositive_doesNotPoisonTheEntry() {
		port.answers(OUTAGE);
		assertThatThrownBy(() -> cache.isUnlocked(USER, POI))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		port.answers(YES);

		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void aHeldPositive_answersThroughAnOutage() {
		port.answers(YES);
		cache.isUnlocked(USER, POI);

		port.answers(OUTAGE);

		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(1);
	}

	@Test
	void aPositiveIsHeldUpToButNotIncludingTheTtl() {
		port.answers(YES);
		cache.isUnlocked(USER, POI);

		now.set(START.plus(CachingPoiUnlocks.TTL).minusNanos(1));
		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(1);

		now.set(START.plus(CachingPoiUnlocks.TTL));
		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void aHitDoesNotExtendTheTtl() {
		port.answers(YES);
		cache.isUnlocked(USER, POI);

		now.set(START.plus(Duration.ofMinutes(9)));
		cache.isUnlocked(USER, POI);
		now.set(START.plus(Duration.ofMinutes(11)));
		cache.isUnlocked(USER, POI);

		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void aRefreshAfterTheTtl_holdsForAFullTtlAgain() {
		port.answers(YES);
		cache.isUnlocked(USER, POI);

		Instant refreshedAt = START.plus(CachingPoiUnlocks.TTL);
		now.set(refreshedAt);
		cache.isUnlocked(USER, POI);
		now.set(refreshedAt.plus(CachingPoiUnlocks.TTL).minusNanos(1));
		cache.isUnlocked(USER, POI);

		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void anExpiredPositive_doesNotAnswerThroughAnOutage() {
		port.answers(YES);
		cache.isUnlocked(USER, POI);

		now.set(START.plus(CachingPoiUnlocks.TTL));
		port.answers(OUTAGE);

		assertThatThrownBy(() -> cache.isUnlocked(USER, POI))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);
	}

	@Test
	void theLimitItselfEvictsNothing() {
		port.answers(YES);
		fillWithOtherUsers(CachingPoiUnlocks.MAX_ENTRIES);
		int callsToFill = port.calls();

		// The eldest of a full cache: one entry too few in the map would have evicted it.
		assertThat(cache.isUnlocked(eldestUser, POI)).isTrue();

		assertThat(port.calls()).isEqualTo(callsToFill);
	}

	@Test
	void pastTheLimit_theLeastRecentlyUsedIsAskedAgainWithTheSameResult() {
		port.answers(YES);
		fillWithOtherUsers(CachingPoiUnlocks.MAX_ENTRIES + 1);
		int callsToFill = port.calls();

		assertThat(cache.isUnlocked(eldestUser, POI)).isTrue();

		assertThat(port.calls()).isEqualTo(callsToFill + 1);
	}

	@Test
	void aReadProtectsAnEntryFromEviction() {
		port.answers(YES);
		fillWithOtherUsers(CachingPoiUnlocks.MAX_ENTRIES);

		assertThat(cache.isUnlocked(eldestUser, POI)).isTrue();
		cache.isUnlocked(UUID.randomUUID(), POI);
		int calls = port.calls();

		assertThat(cache.isUnlocked(eldestUser, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(calls);

		assertThat(cache.isUnlocked(secondEldestUser, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(calls + 1);
	}

	@Test
	void onePositive_isNotAPositiveForAnotherUser() {
		port.answers((user, poi) -> user.equals(USER));

		assertThat(cache.isUnlocked(USER, POI)).isTrue();

		assertThat(cache.isUnlocked(OTHER_USER, POI)).isFalse();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	void onePositive_isNotAPositiveForAnotherPoi() {
		port.answers((user, poi) -> poi.equals(POI));

		assertThat(cache.isUnlocked(USER, POI)).isTrue();

		assertThat(cache.isUnlocked(USER, OTHER_POI)).isFalse();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	@Timeout(20)
	void twoThreadsOnAColdKey_askAtMostOnceExtraAndAgree() throws Exception {
		CyclicBarrier bothInsideThePort = new CyclicBarrier(2);
		port.answers((user, poi) -> {
			await(bothInsideThePort);
			return true;
		});

		Future<Boolean> first = threads.submit(() -> cache.isUnlocked(USER, POI));
		Future<Boolean> second = threads.submit(() -> cache.isUnlocked(USER, POI));

		assertThat(first.get()).isTrue();
		assertThat(second.get()).isTrue();
		assertThat(port.calls()).isEqualTo(2);

		assertThat(cache.isUnlocked(USER, POI)).isTrue();
		assertThat(port.calls()).isEqualTo(2);
	}

	@Test
	@Timeout(20)
	void aSlowPortCall_doesNotStallAnotherUser() throws Exception {
		CountDownLatch slowCallInsideThePort = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		port.answers((user, poi) -> {
			if (user.equals(USER)) {
				slowCallInsideThePort.countDown();
				await(release);
			}
			return true;
		});

		Future<Boolean> slow = threads.submit(() -> cache.isUnlocked(USER, POI));
		slowCallInsideThePort.await();

		Future<Boolean> other = threads.submit(() -> cache.isUnlocked(OTHER_USER, POI));
		assertThat(other.get(10, TimeUnit.SECONDS)).isTrue();

		release.countDown();
		assertThat(slow.get()).isTrue();
	}

	/**
	 * Both lock tests hold the monitor through the clock, because time is read under the lock.
	 * Moving that read out of the lock leaves the gate holding nothing, and they fail loudly.
	 */
	@RepeatedTest(20)
	@Timeout(10)
	void twoHitsNeverShareTheMap() throws Exception {
		port.answers(YES);
		GatedClock clock = new GatedClock();
		PoiUnlocks gatedCache = new CachingPoiUnlocks(port, clock);
		gatedCache.isUnlocked(USER, POI);
		gatedCache.isUnlocked(OTHER_USER, POI);

		Thread holder = daemon(() -> gatedCache.isUnlocked(USER, POI));
		try {
			clock.gate(holder);
			holder.start();
			clock.awaitHolderInside();

			Thread second = daemon(() -> gatedCache.isUnlocked(OTHER_USER, POI));
			second.start();

			assertStopsAtTheLock(second, clock);
		}
		finally {
			clock.release();
		}
	}

	@RepeatedTest(20)
	@Timeout(10)
	void aHitAndAWriteNeverShareTheMap() throws Exception {
		CountDownLatch writerInsideThePort = new CountDownLatch(1);
		CountDownLatch releaseWriter = new CountDownLatch(1);
		port.answers((user, poi) -> {
			if (user.equals(OTHER_USER)) {
				writerInsideThePort.countDown();
				await(releaseWriter);
			}
			return true;
		});
		GatedClock clock = new GatedClock();
		PoiUnlocks gatedCache = new CachingPoiUnlocks(port, clock);
		gatedCache.isUnlocked(USER, POI);

		Thread writer = daemon(() -> gatedCache.isUnlocked(OTHER_USER, POI));
		Thread holder = daemon(() -> gatedCache.isUnlocked(USER, POI));
		try {
			writer.start();
			writerInsideThePort.await();
			clock.gate(holder);
			holder.start();
			clock.awaitHolderInside();

			releaseWriter.countDown();

			assertStopsAtTheLock(writer, clock);
		}
		finally {
			releaseWriter.countDown();
			clock.release();
		}
	}

	private static void assertStopsAtTheLock(Thread thread, GatedClock clock) {
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		while (thread.getState() != Thread.State.BLOCKED && clock.intruder() != thread) {
			assertThat(thread.isAlive()).isTrue();
			assertThat(System.nanoTime()).as("neither blocked nor inside").isLessThan(deadline);
			Thread.onSpinWait();
		}
		assertThat(clock.intruder()).as("a second thread reached the clock inside the lock").isNull();
	}

	private static Thread daemon(Runnable work) {
		Thread thread = new Thread(work);
		thread.setDaemon(true);
		return thread;
	}

	private void fillWithOtherUsers(int entries) {
		for (int i = 0; i < entries; i++) {
			UUID user = UUID.randomUUID();
			if (i == 0) {
				eldestUser = user;
			}
			if (i == 1) {
				secondEldestUser = user;
			}
			cache.isUnlocked(user, POI);
		}
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Parks one thread inside {@link InstantSource#instant()} and notes whoever else gets there. */
	private static final class GatedClock implements InstantSource {

		private final CountDownLatch holderInside = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private final AtomicReference<Thread> intruder = new AtomicReference<>();
		private volatile Thread holder;

		void gate(Thread thread) {
			holder = thread;
		}

		void awaitHolderInside() throws InterruptedException {
			holderInside.await();
		}

		void release() {
			released.countDown();
		}

		Thread intruder() {
			return intruder.get();
		}

		@Override
		public Instant instant() {
			Thread caller = Thread.currentThread();
			if (caller == holder) {
				holderInside.countDown();
				await(released);
			}
			else if (holder != null && holderInside.getCount() == 0 && released.getCount() == 1) {
				intruder.set(caller);
			}
			return START;
		}
	}

	private static final class CountingPort implements PoiUnlocks {

		private final AtomicInteger calls = new AtomicInteger();
		private volatile PoiUnlocks answer = NO;

		void answers(PoiUnlocks answer) {
			this.answer = answer;
		}

		int calls() {
			return calls.get();
		}

		@Override
		public boolean isUnlocked(UUID userId, String osmId) {
			calls.incrementAndGet();
			return answer.isUnlocked(userId, osmId);
		}
	}
}
