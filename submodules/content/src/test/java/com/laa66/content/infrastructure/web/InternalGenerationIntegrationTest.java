package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;

/**
 * The worker's surface over a real socket and real rows: the lease pre-check (M4-11) and the
 * write-back of both outcomes. Every token is the one the lease statement returned, sent the way the
 * worker sends it ({@code Instant.toString()}, microseconds and all), so a hop that loses precision
 * shows up here as a fence that does not open.
 *
 * <p>Spatial is stubbed to say "unlocked" for the gated reads that check what a write-back left.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalGenerationIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");

	private static HttpServer spatial;

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ContentRepository repository;

	@BeforeAll
	static void startSpatial() throws IOException {
		spatial = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		spatial.createContext("/locations/", exchange -> {
			byte[] body = "{\"unlocked\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		spatial.start();
	}

	@AfterAll
	static void stopSpatial() {
		spatial.stop(0);
	}

	@DynamicPropertySource
	static void spatialUri(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri", () -> "http://127.0.0.1:" + spatial.getAddress().getPort());
	}

	// --- the pre-check ----------------------------------------------------------------------

	@Test
	void theLeaseJustTakenIsLive() throws Exception {
		Instant token = lease();

		assertThat(leaseCheck(token)).isEqualTo("{\"live\":true}");
	}

	/** The fence compares for equality: a token rounded on its way is somebody else's token. */
	@Test
	void theSameTokenRoundedToMillisecondsIsNotLive() throws Exception {
		Instant token = leaseWithMicroseconds();

		assertThat(leaseCheck(token)).isEqualTo("{\"live\":true}");
		assertThat(leaseCheck(token.truncatedTo(ChronoUnit.MILLIS))).isEqualTo("{\"live\":false}");
	}

	/** A message from before a takeover: the row is still PROCESSING, under another token. */
	@Test
	void theTokenOfALeaseThatWasTakenOverIsNotLive() throws Exception {
		lease();
		jdbc.sql("UPDATE poi_content SET started_at = now() - interval '6 minutes'").update();
		Instant stale = jdbc.sql("SELECT started_at FROM poi_content").query(OffsetDateTime.class).single().toInstant();
		Instant current = lease();

		assertThat(current).isNotEqualTo(stale);
		assertThat(leaseCheck(stale)).isEqualTo("{\"live\":false}");
		assertThat(leaseCheck(current)).isEqualTo("{\"live\":true}");
	}

	/** Same token, but past GENERATION_LEASE_TIMEOUT: the next POST may take it over at any moment. */
	@Test
	void anExpiredLeaseIsNotLiveEvenWithItsOwnToken() throws Exception {
		Instant token = lease();
		Duration age = ContentRepository.GENERATION_LEASE_TIMEOUT.plusSeconds(1);
		jdbc.sql("UPDATE poi_content SET started_at = started_at - make_interval(secs => :s)")
				.param("s", age.toSeconds()).update();

		assertThat(leaseCheck(token.minus(age))).isEqualTo("{\"live\":false}");
	}

	/** A redelivery after the result landed: the completion released the lease. */
	@Test
	void aCompletedRowHasNoLiveLease() throws Exception {
		Instant token = lease();
		repository.completeGeneration(KEY, token, "Przewodnik", "pl-guide-1");

		assertThat(leaseCheck(token)).isEqualTo("{\"live\":false}");
	}

	/** Also the lease content gave back after an unconfirmed publish (M4-08). */
	@Test
	void aReleasedLeaseIsNotLive() throws Exception {
		Instant token = lease();
		repository.releaseUnpublishedLease(KEY, token);

		assertThat(leaseCheck(token)).isEqualTo("{\"live\":false}");
	}

	@Test
	void noRowHasNoLiveLease() throws Exception {
		assertThat(leaseCheck(Instant.parse("2026-11-09T12:00:00.123456Z"))).isEqualTo("{\"live\":false}");
	}

	// --- the write-back ---------------------------------------------------------------------

	@Test
	void aCompletionIsStoredWithItsPromptVersionAndServedByTheGatedRead() throws Exception {
		Instant token = lease();

		HttpResponse<String> response = postResult(completion(token, "Przewodnik po Ratuszu"));

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(jdbc.sql("""
				SELECT status = 'COMPLETED' AND prompt_version = 'pl-guide-1' AND started_at IS NULL
				FROM poi_content""").query(Boolean.class).single()).isTrue();
		assertThat(gatedRead()).isEqualTo("{\"status\":\"COMPLETED\",\"text\":\"Przewodnik po Ratuszu\"}");
	}

	@Test
	void aRepeatedCompletionChangesNothing() throws Exception {
		Instant token = lease();
		postResult(completion(token, "the first text"));
		String completedAt = jdbc.sql("SELECT completed_at::text FROM poi_content").query(String.class).single();

		HttpResponse<String> again = postResult(completion(token, "another text"));

		assertThat(again.statusCode()).isEqualTo(204);
		assertThat(jdbc.sql("SELECT completed_at::text FROM poi_content").query(String.class).single()).isEqualTo(completedAt);
		assertThat(gatedRead()).contains("the first text");
		assertThat(jdbc.sql("SELECT count(*) FROM poi_content").query(Integer.class).single()).isEqualTo(1);
	}

	/**
	 * The failure path is the strict fence, so this is the test that notices a token that lost a
	 * digit anywhere between the lease statement and here: the row would stay PROCESSING.
	 */
	@Test
	void aFailureLandsAndTheGatedReadServesItsClassWithoutTheDetail() throws Exception {
		Instant token = leaseWithMicroseconds();

		HttpResponse<String> response = postResult(failure(token, "PERMANENT", "gemini: HTTP 400 secret-ish provider body"));

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(jdbc.sql("""
				SELECT status = 'FAILED' AND failed_class = 'PERMANENT' AND started_at IS NULL
				       AND failed_detail = 'gemini: HTTP 400 secret-ish provider body'
				FROM poi_content""").query(Boolean.class).single()).isTrue();
		assertThat(gatedRead()).isEqualTo("{\"status\":\"FAILED\",\"failedClass\":\"PERMANENT\"}");
	}

	@Test
	void aFailureUnderAStaleTokenIsAcceptedAndDropped() throws Exception {
		Instant token = lease();

		HttpResponse<String> response = postResult(failure(token.plusNanos(1_000), "TRANSIENT", "late"));

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(gatedRead()).isEqualTo("{\"status\":\"PROCESSING\"}");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"FAILED\",\"failedClass\":\"TRANSIENT\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"status\":\"FAILED\",\"failedClass\":\"TRANSIENT\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"PROCESSING\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"COMPLETED\",\"text\":\"t\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"COMPLETED\",\"text\":\"t\",\"promptVersion\":\"v\",\"failedClass\":\"QUOTA\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"FAILED\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"FAILED\",\"failedClass\":\"QUOTA\",\"text\":\"t\"}",
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\",\"status\":\"FAILED\",\"failedClass\":\"BROKEN\"}" })
	void aMalformedResultIsA400AndWritesNothing(String body) throws Exception {
		lease();

		assertThat(postResult(body).statusCode()).isEqualTo(400);
		assertThat(gatedRead()).isEqualTo("{\"status\":\"PROCESSING\"}");
	}

	@Test
	void aBlankTextIsA422AndWritesNothing() throws Exception {
		Instant token = lease();

		HttpResponse<String> response = postResult(completion(token, " "));

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(gatedRead()).isEqualTo("{\"status\":\"PROCESSING\"}");
	}

	private Instant lease() {
		return repository.leaseGeneration(KEY).leasedAt();
	}

	/** A token whose sub-millisecond digits are not zero by chance, so losing them is visible. */
	private Instant leaseWithMicroseconds() {
		lease();
		return jdbc.sql("""
				UPDATE poi_content SET started_at = date_trunc('milliseconds', started_at) + interval '123 microseconds'
				RETURNING started_at""").query(OffsetDateTime.class).single().toInstant();
	}

	private String leaseCheck(Instant token) throws Exception {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
				"http://localhost:" + port + "/internal/lease?poiId=node/42&mode=TEXT&lang=pl&leasedAt="
						+ URLEncoder.encode(token.toString(), StandardCharsets.UTF_8))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private static String completion(Instant token, String text) {
		return "{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"" + token
				+ "\",\"status\":\"COMPLETED\",\"text\":\"" + text + "\",\"promptVersion\":\"pl-guide-1\"}";
	}

	private static String failure(Instant token, String failedClass, String detail) {
		return "{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"" + token
				+ "\",\"status\":\"FAILED\",\"failedClass\":\"" + failedClass + "\",\"failedDetail\":\"" + detail + "\"}";
	}

	private HttpResponse<String> postResult(String body) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/results"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String gatedRead() throws Exception {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
				"http://localhost:" + port + "/content?poiId=node/42&mode=TEXT"))
				.header("X-User-Id", UUID.randomUUID().toString()).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}
}
