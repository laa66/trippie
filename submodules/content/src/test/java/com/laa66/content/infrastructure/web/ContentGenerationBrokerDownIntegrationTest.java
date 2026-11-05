package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.PublishBudget;
import com.laa66.content.support.SpatialStub;

/**
 * A dead broker: the test profile points it at a closed port. Every click is a 503, and none of them
 * may spend the row's attempt budget, because no provider was ever called.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentGenerationBrokerDownIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final SpatialStub SPATIAL = SpatialStub.started();

	private static final PoiContentKey KEY = new PoiContentKey("node/open1", ContentMode.TEXT, "pl");

	@DynamicPropertySource
	static void spatial(DynamicPropertyRegistry registry) {
		SPATIAL.register(registry);
	}

	@AfterAll
	static void stopSpatial() {
		SPATIAL.stop();
	}

	private final HttpClient client = HttpClient.newHttpClient();

	private final UUID user = UUID.randomUUID();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ContentRepository repository;

	/**
	 * One click more than the budget: with each failed publish keeping its attempt, the last one
	 * would be a {@code 200 FAILED} and the POI would be parked for a day.
	 */
	@Test
	void clicksAgainstADeadBrokerAre503sThatSpendNoAttempt() {
		for (int click = 0; click <= ContentRepository.MAX_ATTEMPTS_PER_ROW; click++) {
			HttpResponse<String> response = assertTimeoutPreemptively(PublishBudget.PER_PUBLISH, this::post);

			assertThat(response.statusCode()).as("click %d", click).isEqualTo(503);
			assertThat(response.body()).contains("Content generation is temporarily unavailable")
					.doesNotContain("127.0.0.1", "refused", "Connection", "trippie.content");
		}

		assertThat(jdbc.sql("SELECT status || ' ' || failed_class || ' ' || attempts || ' ' || (started_at IS NULL)"
				+ " FROM poi_content").query(String.class).single()).isEqualTo("FAILED TRANSIENT 0 true");
		assertThat(repository.leaseGeneration(KEY).outcome()).isEqualTo(LeaseOutcome.RETRY_GRANTED);
	}

	private HttpResponse<String> post() throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/content/generate"))
				.header("Content-Type", "application/json")
				.header("X-User-Id", user.toString())
				.POST(HttpRequest.BodyPublishers.ofString("{\"poiId\":\"node/open1\",\"mode\":\"TEXT\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
	}
}
