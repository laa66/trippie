package com.laa66.worker.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Gemini API on a real loopback socket: every test of the provider boundary
 * runs against it, none against the real API. Each request is recorded; what it gets back is the
 * current {@link Responder}.
 */
public final class GeminiStub implements AutoCloseable {

	/** One request as it arrived on the wire. */
	public record Recorded(String method, String rawPath, String rawQuery, String apiKeyHeader, String body) {
	}

	@FunctionalInterface
	public interface Responder {

		void respond(HttpExchange exchange) throws IOException;
	}

	private final HttpServer server;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final List<Recorded> requests = new CopyOnWriteArrayList<>();

	private final CountDownLatch released = new CountDownLatch(1);

	private volatile CountDownLatch received = new CountDownLatch(1);

	private volatile Responder responder = exchange -> json(exchange, 200, "{}");

	public GeminiStub() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		server.createContext("/", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
					exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("x-goog-api-key"),
					new String(body, StandardCharsets.UTF_8)));
			received.countDown();
			try {
				responder.respond(exchange);
			}
			finally {
				exchange.close();
			}
		});
		server.setExecutor(executor);
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void respondWith(Responder responder) {
		this.received = new CountDownLatch(1);
		this.responder = responder;
	}

	public void respondJson(int status, String body) {
		respondWith(exchange -> json(exchange, status, body));
	}

	/** Never answers: holds the exchange until {@link #close()}. */
	public void hang() {
		respondWith(exchange -> awaitRelease());
	}

	/** Sends the status line, the headers and the start of the body, then stalls. */
	public void stallInBody() {
		respondWith(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 1_024);
			exchange.getResponseBody().write("{\"candidates\":".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			awaitRelease();
		});
	}

	public boolean awaitRequest(long seconds) throws InterruptedException {
		return received.await(seconds, TimeUnit.SECONDS);
	}

	public List<Recorded> requests() {
		return List.copyOf(requests);
	}

	@Override
	public void close() {
		released.countDown();
		server.stop(0);
		executor.shutdownNow();
	}

	public static void json(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
	}

	private void awaitRelease() {
		try {
			released.await(2, TimeUnit.MINUTES);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
