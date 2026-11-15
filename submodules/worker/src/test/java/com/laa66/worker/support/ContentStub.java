package com.laa66.worker.support;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for content's internal surface with the one rule the worker relies on: a lease is live
 * while the row is {@code PROCESSING} under exactly that token. A completion releases it, a failure
 * under the live token releases it, anything else changes nothing. The real fences, against real
 * rows, are content's {@code InternalGenerationIntegrationTest}; this stub only has to say "no" when
 * content would.
 *
 * <p>Every lease check and every result is recorded as it arrived.
 */
public final class ContentStub implements AutoCloseable {

	/** One row: its status and, while {@code PROCESSING}, the live token. */
	public record Row(String status, Instant token) {
	}

	private final HttpServer server;

	private final Map<String, Row> rows = new ConcurrentHashMap<>();

	private final List<String> leaseQueries = new CopyOnWriteArrayList<>();

	private final List<JsonNode> results = new CopyOnWriteArrayList<>();

	private final AtomicInteger resultFailuresLeft = new AtomicInteger();

	private volatile int resultRefusal;

	public ContentStub() {
		server = HttpStubs.start(this::handle);
	}

	public String baseUrl() {
		return HttpStubs.baseUrl(server);
	}

	/** The row as the lease statement leaves it. */
	public void leased(String osmId, Instant token) {
		rows.put(osmId, new Row("PROCESSING", token));
	}

	/** Another lease took the row over: the old token is dead, the row still PROCESSING. */
	public void takenOver(String osmId) {
		rows.put(osmId, new Row("PROCESSING", Instant.EPOCH));
	}

	public Row row(String osmId) {
		return rows.get(osmId);
	}

	/** The next {@code times} result writes answer 503 and change nothing. */
	public void failResults(int times) {
		resultFailuresLeft.set(times);
	}

	/** Every result write answers {@code status} and changes nothing, as something that is not content. */
	public void refuseResults(int status) {
		resultRefusal = status;
	}

	public List<String> leaseQueries() {
		return List.copyOf(leaseQueries);
	}

	public List<JsonNode> results() {
		return List.copyOf(results);
	}

	public void reset() {
		rows.clear();
		leaseQueries.clear();
		results.clear();
		resultFailuresLeft.set(0);
		resultRefusal = 0;
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if ("GET".equals(exchange.getRequestMethod()) && "/internal/lease".equals(path)) {
			String rawQuery = exchange.getRequestURI().getRawQuery();
			leaseQueries.add(rawQuery);
			Map<String, String> query = query(rawQuery);
			Row row = rows.get(query.get("poiId"));
			boolean live = row != null && "PROCESSING".equals(row.status())
					&& row.token().equals(Instant.parse(query.get("leasedAt")));
			HttpStubs.send(exchange, 200, "{\"live\":" + live + "}");
			return;
		}
		if ("POST".equals(exchange.getRequestMethod()) && "/internal/results".equals(path)) {
			JsonNode result = JsonMapper.shared().readTree(exchange.getRequestBody().readAllBytes());
			results.add(result);
			if (resultRefusal != 0) {
				HttpStubs.send(exchange, resultRefusal, "");
				return;
			}
			if (resultFailuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
				HttpStubs.send(exchange, 503, "");
				return;
			}
			write(result);
			exchange.sendResponseHeaders(204, -1);
			return;
		}
		HttpStubs.send(exchange, 404, "");
	}

	private void write(JsonNode result) {
		String osmId = result.path("key").path("osmId").asString();
		Instant token = Instant.parse(result.path("leasedAt").asString());
		Row row = rows.get(osmId);
		boolean owns = row != null && "PROCESSING".equals(row.status()) && row.token().equals(token);
		if ("COMPLETED".equals(result.path("status").asString()) && (owns || row == null || !"COMPLETED".equals(row.status()))) {
			rows.put(osmId, new Row("COMPLETED", null));
		}
		else if ("FAILED".equals(result.path("status").asString()) && owns) {
			rows.put(osmId, new Row("FAILED", null));
		}
	}

	private static Map<String, String> query(String rawQuery) {
		Map<String, String> params = new HashMap<>();
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}
}
