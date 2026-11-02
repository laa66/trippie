package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Nothing mocked but spatial: real Tomcat, the shipped {@code PoiUnlocks} bean, real rows. A fresh
 * user per test keeps the shipped cache from carrying a positive between tests.
 *
 * <p>The stub answers by POI: {@code node/open*} is unlocked, {@code node/locked*} is not, anything
 * else is a 500.
 *
 * <p>No broker: the test profile points it at a closed port, so every read here also proves that
 * {@code GET /content} does not need one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentReadIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final AtomicInteger spatialRequests = new AtomicInteger();

	private static HttpServer spatial;

	private final HttpClient client = HttpClient.newHttpClient();

	private final UUID user = UUID.randomUUID();

	@Value("${local.server.port}")
	private int port;

	@BeforeAll
	static void startSpatial() throws IOException {
		spatial = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		spatial.createContext("/locations/", ContentReadIntegrationTest::answerUnlockState);
		spatial.start();
	}

	private static void answerUnlockState(HttpExchange exchange) throws IOException {
		spatialRequests.incrementAndGet();
		String path = exchange.getRequestURI().getPath();
		boolean known = path.startsWith("/locations/node/open") || path.startsWith("/locations/node/locked");
		byte[] body = ("{\"unlocked\":" + path.startsWith("/locations/node/open") + "}")
				.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(known ? 200 : 500, body.length);
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

	private HttpResponse<String> get(String query) throws Exception {
		return client.send(request(query).header("X-User-Id", user.toString()).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder request(String query) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/content?" + query));
	}

	private void insertCompleted(String osmId, String text) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, text, completed_at)
				VALUES (?, 'TEXT', 'pl', 'COMPLETED', ?, now())
				""").params(osmId, text).update();
	}

	private void insertProcessing(String osmId) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, started_at)
				VALUES (?, 'TEXT', 'pl', 'PROCESSING', now())
				""").params(osmId).update();
	}

	private void insertFailed(String osmId, String failedDetail) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, failed_class, failed_detail)
				VALUES (?, 'TEXT', 'pl', 'FAILED', 'QUOTA', ?)
				""").params(osmId, failedDetail).update();
	}

	@Test
	void anUnlockedPoiServesItsRow_withTheIdEncodedAsTheClientEncodesIt() throws Exception {
		insertCompleted("node/open1", "Przewodnik po Ratuszu");

		HttpResponse<String> response = get("poiId=node%2Fopen1&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"COMPLETED\",\"text\":\"Przewodnik po Ratuszu\"}");
	}

	@Test
	void aProcessingRowServesStatusAlone() throws Exception {
		insertProcessing("node/open2");

		HttpResponse<String> response = get("poiId=node/open2&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"PROCESSING\"}");
	}

	@Test
	void aFailedRowServesItsClassAndNeverItsDetail() throws Exception {
		insertFailed("node/open3", "raw provider body");

		HttpResponse<String> response = get("poiId=node/open3&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"FAILED\",\"failedClass\":\"QUOTA\"}");
	}

	@Test
	void noRowIsAbsent() throws Exception {
		HttpResponse<String> response = get("poiId=node/open4&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"ABSENT\"}");
	}

	@Test
	void contentOfAnotherModeOrLanguageIsNotThisKey() throws Exception {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, text, completed_at)
				VALUES ('node/open5', 'TEXT', 'en', 'COMPLETED', 'Guide', now()),
				       ('node/open5', 'AUDIO', 'pl', 'COMPLETED', 'Audio', now())
				""").update();

		assertThat(get("poiId=node/open5&mode=TEXT").body()).isEqualTo("{\"status\":\"ABSENT\"}");
	}

	/**
	 * Date is the one header excluded, because it is the clock's and two requests may straddle a
	 * second. Everything else, body included, has to match to the byte.
	 */
	@Test
	void the403OverARealSocketIsIdenticalInEveryContentState() throws Exception {
		HttpResponse<byte[]> noRow = rawLocked();

		insertCompleted("node/locked1", "Przewodnik");
		HttpResponse<byte[]> completed = rawLocked();
		jdbc.sql("DELETE FROM poi_content").update();

		insertFailed("node/locked1", "raw provider body");
		HttpResponse<byte[]> failed = rawLocked();

		assertThat(noRow.statusCode()).isEqualTo(403);
		assertThat(new String(noRow.body(), StandardCharsets.UTF_8)).contains("POI is not unlocked");
		for (HttpResponse<byte[]> other : List.of(completed, failed)) {
			assertThat(other.statusCode()).isEqualTo(noRow.statusCode());
			assertThat(other.body()).isEqualTo(noRow.body());
			assertThat(headersWithoutDate(other.headers())).isEqualTo(headersWithoutDate(noRow.headers()));
		}
	}

	private HttpResponse<byte[]> rawLocked() throws Exception {
		return client.send(request("poiId=node/locked1&mode=TEXT").header("X-User-Id", user.toString()).build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private static Map<String, List<String>> headersWithoutDate(HttpHeaders headers) {
		Map<String, List<String>> kept = new HashMap<>(headers.map());
		assertThat(kept.remove("date")).isNotNull();
		return kept;
	}

	@Test
	void anOutageOfSpatialIsA503EvenWhenTheContentExists() throws Exception {
		insertCompleted("node/broken1", "Przewodnik");

		HttpResponse<String> response = get("poiId=node/broken1&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("Unlock state is temporarily unavailable")
				.doesNotContain("Przewodnik", "spatial", "500");
	}

	@Test
	void aModeThatIsNotServedIsA422WithoutASingleCallToSpatial() throws Exception {
		spatialRequests.set(0);

		assertThat(get("poiId=node/open6&mode=AUDIO").statusCode()).isEqualTo(422);
		assertThat(get("poiId=node/open6&mode=BOTH").statusCode()).isEqualTo(422);

		assertThat(spatialRequests.get()).isZero();
	}

	/**
	 * The empty id joins the malformed ones: it cannot name a POI, so the adapter refuses it without a
	 * call and the answer is the ordinary 403.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "poiId=", "poiId=%20", "poiId=node", "poiId=..%2F..%2Fauth", "poiId=node%2F42%2Fx",
			"poiId=node/1&poiId=node/2" })
	void anIdThatCannotNameAPoiIsTheOrdinary403WithoutACallToSpatial(String poiId) throws Exception {
		spatialRequests.set(0);

		HttpResponse<String> response = get(poiId + "&mode=TEXT");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("POI is not unlocked");
		assertThat(spatialRequests.get()).isZero();
	}

	@Test
	void noUserHeaderIsA400() throws Exception {
		spatialRequests.set(0);

		HttpResponse<String> response = client.send(request("poiId=node/open7&mode=TEXT").build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(spatialRequests.get()).isZero();
	}

	@Test
	void noPoiIdIsA400() throws Exception {
		assertThat(get("mode=TEXT").statusCode()).isEqualTo(400);
	}
}
