package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.support.ContentStub;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.ManualClock;
import com.laa66.worker.support.RabbitContainers;
import com.laa66.worker.support.SpatialStub;

import tools.jackson.databind.JsonNode;

/**
 * The production handler on a live broker: pre-check, POI detail, provider, write-back, with the
 * shipped listener, retry policy, paced adapter and HTTP clients. Content, spatial and Gemini are
 * loopback stubs ({@link ContentStub} keeps content's lease rule: live while {@code PROCESSING} under
 * exactly that token). Every count here is read off a stub, so "zero provider calls" is measured.
 *
 * <p>The pacer runs on a {@link ManualClock}, so its sleeps are counted, not waited. The retry
 * backoff runs in real time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GenerationIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final GeminiStub GEMINI = new GeminiStub();

	private static final ContentStub CONTENT = new ContentStub();

	private static final SpatialStub SPATIAL = new SpatialStub();

	private static final Instant TOKEN = Instant.parse("2026-11-09T12:00:00.123456Z");

	@DynamicPropertySource
	static void brokerAndServices(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
		registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
		registry.add("trippie.content.uri", CONTENT::baseUrl);
		registry.add("trippie.spatial.uri", SPATIAL::baseUrl);
	}

	@AfterAll
	static void stopStubs() {
		GEMINI.close();
		CONTENT.close();
		SPATIAL.close();
	}

	@TestConfiguration
	static class Clock {

		@Bean
		@Primary
		ManualClock manualClock() {
			return new ManualClock(0);
		}

		@Bean
		@Primary
		ProviderCallPacer.Sleeper manualSleeper(ManualClock clock) {
			return clock.sleeper();
		}
	}

	@Autowired
	private ManualClock clock;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private RabbitAdmin admin;

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@BeforeEach
	void reset() {
		admin.purgeQueue(GenerationTopology.QUEUE);
		admin.purgeQueue(GenerationTopology.DEAD_LETTER_QUEUE);
		CONTENT.reset();
		SPATIAL.reset();
		GEMINI.respondJson(200, GeminiStub.ok("Przewodnik po Ratuszu"));
		// Past any earlier provider call, so the first call of a test never waits for the pacer.
		clock.advance(Duration.ofMinutes(1));
	}

	@Test
	void aTaskEndsAsACompletionWithTheTokenTheTextAndThePromptVersion() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/1");

		publish("node/1");

		await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(CONTENT.row("node/1").status()));
		JsonNode result = CONTENT.results().getFirst();
		assertThat(result.path("leasedAt").asString()).isEqualTo("2026-11-09T12:00:00.123456Z");
		assertThat(result.path("text").asString()).isEqualTo("Przewodnik po Ratuszu");
		assertThat(result.path("promptVersion").asString()).isEqualTo("pl-guide-1");
		assertThat(GEMINI.requests()).hasSize(calls + 1);
		assertThat(GEMINI.requests().getLast().body()).contains("Ratusz").doesNotContain("ignore all previous");
		assertThat(SPATIAL.paths()).containsExactly("/locations/node/1");
		assertSettled(0);
	}

	/** At-least-once delivery: the same message again after its result landed costs nothing. */
	@Test
	void aRedeliveredTaskMakesNoProviderCallAndWritesNothing() throws Exception {
		ready("node/2");
		publish("node/2");
		await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(CONTENT.row("node/2").status()));
		int calls = GEMINI.requests().size();

		publish("node/2");

		await().atMost(Duration.ofSeconds(10)).until(() -> CONTENT.leaseQueries().size() == 2);
		Thread.sleep(1_000);
		assertThat(GEMINI.requests()).hasSize(calls);
		assertThat(CONTENT.results()).hasSize(1);
		assertThat(SPATIAL.paths()).hasSize(1);
		assertSettled(0);
	}

	/** A message from before a takeover, or one whose publish content reported as failed (M4-08). */
	@Test
	void aTaskWhoseTokenIsNoLongerTheRowsMakesNoProviderCall() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/3");
		CONTENT.takenOver("node/3");

		publish("node/3");

		await().atMost(Duration.ofSeconds(10)).until(() -> CONTENT.leaseQueries().size() == 1);
		Thread.sleep(1_000);
		assertThat(GEMINI.requests()).hasSize(calls);
		assertThat(SPATIAL.paths()).isEmpty();
		assertThat(CONTENT.results()).isEmpty();
		assertSettled(0);
	}

	/** Dopisek M4-09 LOW-3: the lease is checked again before the retry, and the retry finds it dead. */
	@Test
	void aTokenInvalidatedBetweenAttemptsStopsTheRetryBeforeTheProvider() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/4");
		GEMINI.respondWith(exchange -> {
			CONTENT.takenOver("node/4");
			GeminiStub.json(exchange, 503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}");
		});

		publish("node/4");

		await().atMost(Duration.ofSeconds(20)).until(() -> CONTENT.leaseQueries().size() == 2);
		Thread.sleep(1_000);
		assertThat(GEMINI.requests()).hasSize(calls + 1);
		assertThat(CONTENT.results()).isEmpty();
		assertSettled(0);
	}

	@Test
	void aPermanentProviderFailureIsWrittenBackAndDeadLettered() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/5");
		GEMINI.respondJson(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad\"}}");

		publish("node/5");

		await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);
		Thread.sleep(1_000);
		assertThat(GEMINI.requests()).hasSize(calls + 1);
		assertThat(CONTENT.row("node/5").status()).isEqualTo("FAILED");
		JsonNode result = CONTENT.results().getFirst();
		assertThat(result.path("status").asString()).isEqualTo("FAILED");
		assertThat(result.path("failedClass").asString()).isEqualTo("PERMANENT");
		assertThat(result.path("failedDetail").asString()).startsWith("gemini: HTTP 400 INVALID_ARGUMENT");
		assertThat(result.path("leasedAt").asString()).isEqualTo("2026-11-09T12:00:00.123456Z");
		assertSettled(1);
	}

	@Test
	void anUnknownPoiIsAPermanentFailureWithoutAProviderCall() throws Exception {
		int calls = GEMINI.requests().size();
		CONTENT.leased("node/6", TOKEN);

		publish("node/6");

		await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);
		Thread.sleep(1_000);
		assertThat(GEMINI.requests()).hasSize(calls);
		assertThat(SPATIAL.paths()).hasSize(1);
		assertThat(CONTENT.results().getFirst().path("failedClass").asString()).isEqualTo("PERMANENT");
		assertThat(CONTENT.results().getFirst().path("failedDetail").asString())
				.isEqualTo("spatial does not know POI node/6");
		assertSettled(1);
	}

	@Test
	void anUnavailableSpatialIsRetriedThenWrittenBackAsTransient() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/7");
		SPATIAL.unavailable(503);

		publish("node/7");

		await().atMost(Duration.ofSeconds(40)).until(() -> deadLetters() == 1);
		assertThat(SPATIAL.paths()).hasSize(3);
		assertThat(GEMINI.requests()).hasSize(calls);
		assertThat(CONTENT.results()).hasSize(1);
		assertThat(CONTENT.results().getFirst().path("failedClass").asString()).isEqualTo("TRANSIENT");
		assertSettled(1);
	}

	@Test
	void aWriteBackThatFailsOnceLandsOnTheRetryWithoutASecondProviderCall() throws Exception {
		int calls = GEMINI.requests().size();
		ready("node/8");
		CONTENT.failResults(1);

		publish("node/8");

		await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(CONTENT.row("node/8").status()));
		assertThat(GEMINI.requests()).hasSize(calls + 1);
		assertThat(CONTENT.results()).hasSize(2);
		assertSettled(0);
	}

	/**
	 * Review M4-09, repeated on the real handler: the pacer sits inside the {@code TextGenerator} the
	 * handler gets, so five paid calls in a row sleep exactly four times, 4 s each, and tasks the
	 * pre-check settles never touch it.
	 */
	@Test
	void thePacerSleepsBeforeEveryPaidCallButTheFirstAndNeverForAPreCheck() {
		int calls = GEMINI.requests().size();
		int sleeps = clock.sleeps().size();
		List<String> ids = List.of("node/11", "node/12", "node/13", "node/14", "node/15");
		ids.forEach(this::ready);

		publishWhileStopped(ids);

		await().atMost(Duration.ofSeconds(30))
				.until(() -> ids.stream().allMatch(id -> "COMPLETED".equals(CONTENT.row(id).status())));
		assertThat(GEMINI.requests()).hasSize(calls + 5);
		assertThat(clock.sleeps().subList(sleeps, clock.sleeps().size()))
				.containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4));

		int sleepsBefore = clock.sleeps().size();
		publishWhileStopped(ids);

		await().atMost(Duration.ofSeconds(10)).until(() -> CONTENT.leaseQueries().size() == 10);
		assertThat(clock.sleeps()).hasSize(sleepsBefore);
		assertThat(GEMINI.requests()).hasSize(calls + 5);
	}

	private void ready(String osmId) {
		CONTENT.leased(osmId, TOKEN);
		SPATIAL.poi(osmId, "Ratusz", "architecture");
	}

	private void publish(String osmId) {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, message(osmId));
	}

	static Message message(String osmId) {
		return MessageBuilder.withBody(("{\"key\":{\"osmId\":\"" + osmId
				+ "\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-11-09T12:00:00.123456Z\"}")
						.getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();
	}

	private void publishWhileStopped(List<String> ids) {
		var container = listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID);
		container.stop();
		try {
			ids.forEach(this::publish);
			await().atMost(Duration.ofSeconds(5)).until(() -> workQueue() == ids.size());
		}
		finally {
			container.start();
		}
	}

	private void assertSettled(int deadLetters) {
		assertThat(workQueue()).isZero();
		assertThat(deadLetters()).isEqualTo(deadLetters);
	}

	private long deadLetters() {
		return admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount();
	}

	private long workQueue() {
		return admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount();
	}
}
