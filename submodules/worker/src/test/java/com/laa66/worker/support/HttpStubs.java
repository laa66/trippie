package com.laa66.worker.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** A loopback {@link HttpServer} on a free port, for the stand-ins of content and spatial. */
final class HttpStubs {

	private HttpStubs() {
	}

	static HttpServer start(HttpHandler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> {
				try {
					handler.handle(exchange);
				}
				finally {
					exchange.close();
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String baseUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	static void send(HttpExchange exchange, int status, String json) throws IOException {
		send(exchange, status, "application/json", json);
	}

	static void send(HttpExchange exchange, int status, String contentType, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
	}
}
