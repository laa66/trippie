package com.laa66.spatial.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.infrastructure.config.SpatialConfig;

/**
 * The other half of {@link AuthUserPreferencesTimeoutTest}: auth down rather than auth hung. Nothing
 * listens on the configured port, so the connection is refused immediately, and the adapter must
 * still produce the strict answer — this is the only failure mode on the unlock path that a stopped
 * container actually produces, and the one whose speed is the reason the sibling test's lower bound
 * means anything.
 *
 * <p>A separate class because the base URI is fixed when the bean is built, so one context cannot
 * point at a hung server and a dead port at once. The bean is otherwise identical: the real wiring
 * from {@code SpatialConfig}, a real socket address, the shipped {@link SpatialConfig#AUTH_TIMEOUT}.
 */
@SpringBootTest
class AuthUserPreferencesConnectionRefusedTest {

	private static final int CLOSED_PORT = reserveThenReleasePort();

	@Autowired
	private UserPreferences userPreferences;

	@DynamicPropertySource
	static void authUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.auth.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + CLOSED_PORT);
	}

	/** Lets the OS pick a free port, then closes the socket so connecting to it is refused. */
	private static int reserveThenReleasePort() {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Test
	@Timeout(20)
	void refusedConnection_failsClosedImmediately() {
		long startedAt = System.nanoTime();

		boolean requireProximity = userPreferences.requireProximity(UUID.randomUUID());

		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		assertThat(requireProximity).isTrue();
		// The point of the assertion: refusal is reported, not waited out. Generous against a loaded
		// runner, but far below the budget that a hung auth would burn.
		assertThat(elapsed).isLessThan(SpatialConfig.AUTH_TIMEOUT.dividedBy(2));
	}
}
