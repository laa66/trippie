package com.laa66.content.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.infrastructure.config.ContentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Proves the shipped bean against a real socket, which the mock in
 * {@link SpatialPoiUnlocksAdapterTest} cannot: it replaces the request factory. Only the URI is
 * redirected here; the budget is the shipped {@link ContentConfig#SPATIAL_TIMEOUT}.
 *
 * <p>The lower bound rules out the trivial pass, since a refused connection also fails fast. The
 * method timeout turns an unbounded read into a failure instead of a hung build. Costs about two
 * seconds on purpose: a test-only timeout would leave the shipped value proved nowhere.
 */
@SpringBootTest
class SpatialPoiUnlocksTimeoutTest {

	private static final CountDownLatch release = new CountDownLatch(1);
	private static final AtomicReference<String> rawPath = new AtomicReference<>();
	private static final AtomicReference<String> userHeader = new AtomicReference<>();

	private static HttpServer spatial;
	private static ExecutorService serverExecutor;

	@Autowired
	private PoiUnlocks poiUnlocks;

	@BeforeAll
	static void startSpatial() throws IOException {
		spatial = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		spatial.createContext("/locations/node/1/unlocked", exchange -> hang(exchange));
		spatial.createContext("/locations/node/2/unlocked", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 1024);
			exchange.getResponseBody().write("{\"unlocked\":".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			hang(exchange);
		});
		spatial.createContext("/locations/node/42/unlocked", exchange -> {
			rawPath.set(exchange.getRequestURI().getRawPath());
			userHeader.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
			byte[] body = "{\"unlocked\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		serverExecutor = Executors.newFixedThreadPool(4);
		spatial.setExecutor(serverExecutor);
		spatial.start();
	}

	private static void hang(HttpExchange exchange) {
		try {
			release.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		exchange.close();
	}

	@AfterAll
	static void stopSpatial() {
		release.countDown();
		if (spatial != null) {
			spatial.stop(0);
		}
		if (serverExecutor != null) {
			serverExecutor.shutdownNow();
		}
	}

	@DynamicPropertySource
	static void spatialUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
						+ spatial.getAddress().getPort());
	}

	/** The bounds below scale with the constant, so they cannot see it change. */
	@Test
	void theBudgetIsOneSecond() {
		assertThat(ContentConfig.SPATIAL_TIMEOUT).isEqualTo(Duration.ofSeconds(1));
	}

	/** {@code %2F} here is the dead end: Tomcat rejects it, and only real bytes can show it is gone. */
	@Test
	@Timeout(20)
	void theIdLeavesAsTwoRawSegments_withTheSubjectInTheHeader() {
		UUID user = UUID.randomUUID();

		assertThat(poiUnlocks.isUnlocked(user, "node/42")).isTrue();

		assertThat(rawPath.get()).isEqualTo("/locations/node/42/unlocked");
		assertThat(userHeader.get()).isEqualTo(user.toString());
	}

	@Test
	@Timeout(20)
	void spatialThatNeverAnswers_timesOutAsUnavailable() {
		assertTimesOutAsUnavailable("node/1");
	}

	@Test
	@Timeout(20)
	void spatialThatStallsMidBody_timesOutAsUnavailable() {
		assertTimesOutAsUnavailable("node/2");
	}

	private void assertTimesOutAsUnavailable(String osmId) {
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> poiUnlocks.isUnlocked(UUID.randomUUID(), osmId))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		assertThat(elapsed)
				.isGreaterThan(ContentConfig.SPATIAL_TIMEOUT.dividedBy(2))
				.isLessThan(ContentConfig.SPATIAL_TIMEOUT.multipliedBy(10));
	}
}
