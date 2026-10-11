package com.laa66.spatial.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.infrastructure.config.SpatialConfig;
import com.sun.net.httpserver.HttpServer;

/**
 * Proves the configured timeout is real, which {@link AuthUserPreferencesAdapterTest} cannot:
 * {@code MockRestServiceServer} replaces the request factory, so a faked timeout there would
 * demonstrate nothing whatsoever about how the client is configured.
 *
 * <p>So this test uses a real socket and the real bean. A loopback {@link HttpServer} accepts the
 * connection and then never answers, and the {@link UserPreferences} bean under test is the one
 * {@code SpatialConfig} wires — only {@code trippie.auth.uri} is redirected here, while the budget
 * is the shipped {@link SpatialConfig#AUTH_TIMEOUT}. The chain it closes end to end is therefore:
 * the shipped value, the wiring that passes it to the request factory, the factory's ability to
 * abort a hung read, and the adapter's conversion of that into the strict answer.
 *
 * <p>Two assertions make it an assertion about the timeout specifically. The lower bound rules out
 * the trivial pass: a refused connection returns {@code true} in milliseconds too (asserted by
 * {@link AuthUserPreferencesConnectionRefusedTest}) and would prove only that the adapter catches
 * exceptions, so having waited, the call must have connected and hung. The upper bound is what the
 * budget buys. Both bounds are derived from the shipped value rather than written out, so changing
 * it cannot leave this test asserting a number nothing uses. The method timeout exists so that an
 * unbounded read fails the build instead of hanging it.
 *
 * <p>It costs roughly one second of wall clock, accepted deliberately: the alternative — a short
 * test-only timeout — would move the shipped value out of the proof and leave the thing most worth
 * knowing, that production waits one second rather than forever, asserted nowhere.
 */
@SpringBootTest
class AuthUserPreferencesTimeoutTest {

	private static final CountDownLatch release = new CountDownLatch(1);

	private static HttpServer authServer;
	private static ExecutorService serverExecutor;

	@Autowired
	private UserPreferences userPreferences;

	@BeforeAll
	static void startHangingAuth() throws IOException {
		authServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		authServer.createContext("/settings", exchange -> {
			// Accept, then never respond: the client's connect succeeds and its read is what hangs.
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		serverExecutor = Executors.newFixedThreadPool(2);
		authServer.setExecutor(serverExecutor);
		authServer.start();
	}

	/**
	 * Null-guarded because a failure in {@link #startHangingAuth} would otherwise be reported as an
	 * NPE in teardown, hiding the reason the server never came up.
	 */
	@AfterAll
	static void stopHangingAuth() {
		release.countDown();
		if (authServer != null) {
			authServer.stop(0);
		}
		if (serverExecutor != null) {
			serverExecutor.shutdownNow();
		}
	}

	@DynamicPropertySource
	static void authUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.auth.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
						+ authServer.getAddress().getPort());
	}

	@Test
	@Timeout(20)
	void hangingAuth_timesOutAndFailsClosed() {
		long startedAt = System.nanoTime();

		boolean requireProximity = userPreferences.requireProximity(UUID.randomUUID());

		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		assertThat(requireProximity).isTrue();
		assertThat(elapsed)
				.isGreaterThan(SpatialConfig.AUTH_TIMEOUT.dividedBy(2))
				.isLessThan(SpatialConfig.AUTH_TIMEOUT.multipliedBy(10));
	}
}
