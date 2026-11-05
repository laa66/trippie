package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.infrastructure.messaging.GenerationTopology;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.RabbitContainers;
import com.laa66.content.support.SpatialStub;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /content/generate} with nothing mocked but spatial: real Tomcat, real Postgres, a real
 * broker. Every row of the M4-08 acceptance table is a test here, and each one counts what it
 * matters for — messages in the work queue, rows in the table, calls to spatial — rather than only
 * reading the response.
 *
 * <p>A fresh user per test keeps the shipped unlock cache from carrying a positive between tests.
 * The broker-down and memory-alarm rows live in their own classes, because each needs a broker with
 * a history this class must not share.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentGenerationIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final SpatialStub SPATIAL = SpatialStub.started();

	@DynamicPropertySource
	static void dependencies(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
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
	private AmqpAdmin admin;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private DataSource dataSource;

	@MockitoSpyBean
	private EventPublisher publisher;

	@BeforeEach
	void emptyTheQueueAndTheCount() {
		admin.purgeQueue(GenerationTopology.QUEUE);
		SPATIAL.resetCount();
	}

	// --- the requests that must not reach the table or the broker ----------------------------

	@ParameterizedTest
	@ValueSource(strings = { "AUDIO", "BOTH" })
	void aModeThatIsNotServedIsA422BeforeSpatialIsAsked(String mode) throws Exception {
		HttpResponse<String> response = post(user, "node/open1", mode);

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(SPATIAL.requests()).isZero();
		assertNothingHappened();
	}

	@Test
	void aLockedPoiIsA403WithNoRowAndNoMessage() throws Exception {
		HttpResponse<String> response = post(user, "node/locked1", "TEXT");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("POI is not unlocked");
		assertNothingHappened();
	}

	/** Spatial answers {@code false} for a POI it does not know, so this is a decision and not luck. */
	@Test
	void anUnknownPoiIsA403BecauseItCannotBeUnlocked() throws Exception {
		HttpResponse<String> response = post(user, "node/unknown1", "TEXT");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(SPATIAL.requests()).isEqualTo(1);
		assertNothingHappened();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "node", "../../auth", "node/42/x" })
	void anIdThatCannotNameAPoiIsA403WithoutACallToSpatial(String poiId) throws Exception {
		HttpResponse<String> response = post(user, poiId, "TEXT");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(SPATIAL.requests()).isZero();
		assertNothingHappened();
	}

	@Test
	void anOutageOfSpatialIsA503WithNoRowAndNoMessage() throws Exception {
		HttpResponse<String> response = post(user, "node/broken1", "TEXT");

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("Unlock state is temporarily unavailable");
		assertNothingHappened();
	}

	@Test
	void noUserHeaderIsA400() throws Exception {
		HttpResponse<String> response = client.send(request().POST(body("node/open1", "TEXT")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(SPATIAL.requests()).isZero();
		assertNothingHappened();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"mode\":\"TEXT\"}", "{\"poiId\":\"node/open1\"}", "{\"poiId\":\"node/open1\",\"mode\":\"VIDEO\"}",
			"", "not json" })
	void anIncompleteOrUnreadableBodyIsA400(String body) throws Exception {
		HttpResponse<String> response = client.send(request().header("X-User-Id", user.toString())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(SPATIAL.requests()).isZero();
		assertNothingHappened();
	}

	// --- the five lease outcomes ---------------------------------------------------------------

	@Test
	void aGrantedLeaseIs202AndExactlyOneMessageCarryingTheRowsToken() throws Exception {
		HttpResponse<String> response = post(user, "node/open2", "TEXT");

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.body()).isEqualTo("{\"status\":\"PROCESSING\"}");
		assertThat(messagesInQueue()).isEqualTo(1);
		GenerateContentTask task = decode(rabbit.receive(GenerationTopology.QUEUE, 5_000));
		assertThat(task.key()).isEqualTo(new PoiContentKey("node/open2", ContentMode.TEXT, "pl"));
		assertThat(task.leasedAt()).isEqualTo(startedAt("node/open2"));
		assertThat(attempts("node/open2")).isEqualTo(1);
	}

	/** Flow 13: the second click while the first lease is live. */
	@Test
	void aLeaseHeldByAnotherAttemptIs202AndNoNewMessage() throws Exception {
		post(user, "node/open3", "TEXT");
		admin.purgeQueue(GenerationTopology.QUEUE);

		HttpResponse<String> response = post(user, "node/open3", "TEXT");

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.body()).isEqualTo("{\"status\":\"PROCESSING\"}");
		assertNoMessage();
		assertThat(attempts("node/open3")).isEqualTo(1);
	}

	/** Status only: the text has one place to be read from, {@code GET /content}. */
	@Test
	void completedContentIs200WithoutTextAndWithoutAPublish() throws Exception {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, text, completed_at)
				VALUES ('node/open4', 'TEXT', 'pl', 'COMPLETED', 'Przewodnik', now())
				""").update();

		HttpResponse<String> response = post(user, "node/open4", "TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"COMPLETED\"}");
		assertNoMessage();
	}

	@Test
	void aFailedRowWithBudgetLeftIsLeasedAgainAndPublished() throws Exception {
		seedFailed("node/open5", ContentRepository.MAX_ATTEMPTS_PER_ROW - 1);

		HttpResponse<String> response = post(user, "node/open5", "TEXT");

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(messagesInQueue()).isEqualTo(1);
		assertThat(attempts("node/open5")).isEqualTo(ContentRepository.MAX_ATTEMPTS_PER_ROW);
	}

	/** Without the cap, "Retry" is a loop over a paid API. */
	@Test
	void aRowOutOfBudgetIs200FailedAndNothingIsPublished() throws Exception {
		seedFailed("node/open6", ContentRepository.MAX_ATTEMPTS_PER_ROW);

		HttpResponse<String> response = post(user, "node/open6", "TEXT");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"FAILED\"}");
		assertNoMessage();
		assertThat(attempts("node/open6")).isEqualTo(ContentRepository.MAX_ATTEMPTS_PER_ROW);
	}

	// --- the cross-cutting rows -----------------------------------------------------------------

	/** Two users, two tabs, one POI: whoever loses the lease is told it is processing. */
	@Test
	void twoParallelPostsForOnePoiPublishOneMessage() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				String poiId = "node/openrace" + round;
				CountDownLatch start = new CountDownLatch(1);
				List<Future<HttpResponse<String>>> responses = new ArrayList<>();
				for (int caller = 0; caller < 2; caller++) {
					UUID callerId = UUID.randomUUID();
					responses.add(pool.submit(() -> {
						start.await();
						return post(callerId, poiId, "TEXT");
					}));
				}
				start.countDown();

				for (Future<HttpResponse<String>> response : responses) {
					assertThat(response.get().statusCode()).isEqualTo(202);
					assertThat(response.get().body()).isEqualTo("{\"status\":\"PROCESSING\"}");
				}
				assertThat(messagesInQueue()).as("round %d", round).isEqualTo(1);
				assertThat(attempts(poiId)).isEqualTo(1);
				admin.purgeQueue(GenerationTopology.QUEUE);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	/**
	 * POST and GET ask the same {@code PoiUnlocks} bean, so the positive the POST paid for serves
	 * every poll after it. This is the whole saving decision 4 relies on, counted on the stub.
	 */
	@Test
	void readsAfterASuccessfulPostCostNoFurtherCallToSpatial() throws Exception {
		assertThat(post(user, "node/open7", "TEXT").statusCode()).isEqualTo(202);
		assertThat(SPATIAL.requests()).isEqualTo(1);

		for (int poll = 0; poll < 10; poll++) {
			HttpResponse<String> read = client.send(HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + "/content?poiId=node/open7&mode=TEXT"))
					.header("X-User-Id", user.toString()).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(read.statusCode()).isEqualTo(200);
			assertThat(read.body()).isEqualTo("{\"status\":\"PROCESSING\"}");
		}

		assertThat(SPATIAL.requests()).isEqualTo(1);
	}

	/**
	 * A publish can take the whole confirm wait. Inside a transaction it would hold a pooled
	 * connection and the row lock for as long, so the publish is checked at the moment it runs:
	 * no transaction on the thread and no connection checked out of the pool.
	 */
	@Test
	void thePublishRunsOutsideAnyTransactionAndHoldsNoConnection() throws Exception {
		HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
		List<String> observed = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			observed.add("transaction=" + TransactionSynchronizationManager.isActualTransactionActive()
					+ " activeConnections=" + pool.getHikariPoolMXBean().getActiveConnections());
			return invocation.callRealMethod();
		}).when(publisher).publish(any());

		assertThat(post(user, "node/open8", "TEXT").statusCode()).isEqualTo(202);

		assertThat(observed).containsExactly("transaction=false activeConnections=0");
	}

	// --- helpers ------------------------------------------------------------------------------

	private HttpResponse<String> post(UUID userId, String poiId, String mode) throws Exception {
		return client.send(request().header("X-User-Id", userId.toString()).POST(body(poiId, mode)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder request() {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/content/generate"))
				.header("Content-Type", "application/json");
	}

	private static HttpRequest.BodyPublisher body(String poiId, String mode) {
		return HttpRequest.BodyPublishers.ofString(
				JsonMapper.shared().writeValueAsString(Map.of("poiId", poiId, "mode", mode)));
	}

	private void seedFailed(String osmId, int attempts) {
		jdbc.sql("""
				INSERT INTO poi_content (osm_id, mode, lang, status, attempts, failed_class, last_attempt_at)
				VALUES (?, 'TEXT', 'pl', 'FAILED', ?, 'TRANSIENT', now() - interval '1 hour')
				""").params(osmId, attempts).update();
	}

	private int attempts(String osmId) {
		return jdbc.sql("SELECT attempts FROM poi_content WHERE osm_id = ?").param(osmId).query(Integer.class).single();
	}

	private Instant startedAt(String osmId) {
		return jdbc.sql("SELECT started_at FROM poi_content WHERE osm_id = ?").param(osmId)
				.query(OffsetDateTime.class).single().toInstant();
	}

	private long messagesInQueue() {
		return admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount();
	}

	/** Exact rather than waited for: a response is written only after the publish has been confirmed. */
	private void assertNoMessage() {
		assertThat(messagesInQueue()).as("a message was published").isZero();
	}

	private void assertNothingHappened() {
		assertNoMessage();
		assertThat(jdbc.sql("SELECT count(*) FROM poi_content").query(Integer.class).single()).isZero();
	}

	private static GenerateContentTask decode(Message message) {
		assertThat(message).as("nothing arrived in the work queue").isNotNull();
		return JsonMapper.shared().readValue(new String(message.getBody(), StandardCharsets.UTF_8),
				GenerateContentTask.class);
	}
}
