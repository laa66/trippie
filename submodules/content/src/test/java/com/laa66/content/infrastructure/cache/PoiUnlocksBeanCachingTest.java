package com.laa66.content.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.content.domain.port.out.PoiUnlocks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The shipped bean against a real socket: the only proof that what consumers inject is the cache
 * and that it really sits in front of spatial.
 */
@SpringBootTest
class PoiUnlocksBeanCachingTest {

	private static final AtomicInteger requests = new AtomicInteger();

	private static HttpServer spatial;

	@Autowired
	private PoiUnlocks poiUnlocks;

	@BeforeAll
	static void startSpatial() throws IOException {
		spatial = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		spatial.createContext("/locations/node/1/unlocked", exchange -> answer(exchange, true));
		spatial.createContext("/locations/node/2/unlocked", exchange -> answer(exchange, false));
		spatial.start();
	}

	private static void answer(HttpExchange exchange, boolean unlocked) throws IOException {
		requests.incrementAndGet();
		byte[] body = ("{\"unlocked\":" + unlocked + "}").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	@AfterAll
	static void stopSpatial() {
		spatial.stop(0);
	}

	@DynamicPropertySource
	static void spatialUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
						+ spatial.getAddress().getPort());
	}

	@Test
	void theInjectedPortAsksSpatialOnceForAPositiveAndEveryTimeForANegative() {
		UUID user = UUID.randomUUID();
		requests.set(0);

		assertThat(poiUnlocks.isUnlocked(user, "node/1")).isTrue();
		assertThat(poiUnlocks.isUnlocked(user, "node/1")).isTrue();
		assertThat(requests.get()).isEqualTo(1);

		assertThat(poiUnlocks.isUnlocked(user, "node/2")).isFalse();
		assertThat(poiUnlocks.isUnlocked(user, "node/2")).isFalse();
		assertThat(requests.get()).isEqualTo(3);
	}
}
