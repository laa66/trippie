package com.laa66.content.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.infrastructure.config.ContentConfig;

/**
 * Nothing listening on the port, rather than a server that hangs. What a stopped container produces
 * in compose was not measured; a name that no longer resolves is covered by
 * {@link SpatialPoiUnlocksUnresolvedHostTest}. A separate class from
 * {@link SpatialPoiUnlocksTimeoutTest} because the base URI is fixed when the bean is built.
 */
@SpringBootTest
class SpatialPoiUnlocksConnectionRefusedTest {

	private static final int CLOSED_PORT = reserveThenReleasePort();

	@Autowired
	private PoiUnlocks poiUnlocks;

	@DynamicPropertySource
	static void spatialUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + CLOSED_PORT);
	}

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
	void refusedConnection_isUnavailableImmediately() {
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> poiUnlocks.isUnlocked(UUID.randomUUID(), "node/42"))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
				.isLessThan(ContentConfig.SPATIAL_TIMEOUT.dividedBy(2));
	}
}
