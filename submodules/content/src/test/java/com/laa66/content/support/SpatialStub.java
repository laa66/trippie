package com.laa66.content.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Spatial's unlock-state endpoint on a real socket, answering by POI: {@code node/open*} is unlocked,
 * {@code node/locked*} and {@code node/unknown*} are not (spatial answers {@code false} for a POI it
 * does not know), anything else is a 500. Every request is counted, because what the cache saves is
 * a number of calls.
 */
public final class SpatialStub {

	private final AtomicInteger requests = new AtomicInteger();

	private final HttpServer server;

	private SpatialStub(HttpServer server) {
		this.server = server;
	}

	public static SpatialStub started() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			SpatialStub stub = new SpatialStub(server);
			server.createContext("/locations/", stub::answer);
			server.start();
			return stub;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void answer(HttpExchange exchange) throws IOException {
		requests.incrementAndGet();
		String path = exchange.getRequestURI().getPath();
		boolean open = path.startsWith("/locations/node/open");
		boolean known = open || path.startsWith("/locations/node/locked") || path.startsWith("/locations/node/unknown");
		byte[] body = ("{\"unlocked\":" + open + "}").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(known ? 200 : 500, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	public void register(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort());
	}

	public int requests() {
		return requests.get();
	}

	public void resetCount() {
		requests.set(0);
	}

	public void stop() {
		server.stop(0);
	}
}
