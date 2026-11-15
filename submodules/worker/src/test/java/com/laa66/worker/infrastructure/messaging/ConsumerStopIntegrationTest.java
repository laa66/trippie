package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.support.ContentStub;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.RabbitContainers;
import com.laa66.worker.support.SpatialStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * No task can be served, whatever the task: the provider refuses the key (review M4-10, MEDIUM-1), or
 * content or spatial answers as a service the worker is not meant to talk to (review M4-11, LOW-3).
 * The task goes back to the queue and stays there, nothing is dead-lettered or written back, the
 * listener stops, and {@code /health} is DOWN with the reason, as when the key file is missing
 * ({@code NoApiKeyIntegrationTest}).
 *
 * <p>Production wiring from the broker to the stubs. Each scenario has its own broker and context:
 * a stopped listener and a requeued task are exactly the history the next one must not inherit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsumerStopIntegrationTest {

	private static final Instant TOKEN = Instant.parse("2026-10-31T12:00:00.123456Z");

	private static final String TASK =
			"{\"key\":{\"osmId\":\"node/1\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}";

	@Nested
	class ProviderRejectsTheKey extends Scenario {

		private static final RabbitMQContainer BROKER = RabbitContainers.started();

		private static final GeminiStub GEMINI = new GeminiStub();

		private static final ContentStub CONTENT = new ContentStub();

		private static final SpatialStub SPATIAL = new SpatialStub();

		@DynamicPropertySource
		static void services(DynamicPropertyRegistry registry) throws Exception {
			RabbitContainers.register(registry, BROKER);
			registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
			registry.add("trippie.content.uri", CONTENT::baseUrl);
			registry.add("trippie.spatial.uri", SPATIAL::baseUrl);
			CONTENT.leased("node/1", TOKEN);
			SPATIAL.poi("node/1", "Ratusz", "architecture");
			String key = Files.readString(Path.of("src/test/resources/fake-gemini.key")).strip();
			GEMINI.respondJson(401, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\",\"message\":\"key " + key
					+ " is not valid\"}}");
		}

		@AfterAll
		static void stopStubs() {
			GEMINI.close();
			CONTENT.close();
			SPATIAL.close();
		}

		@Test
		void theTaskStaysInTheQueueTheListenerStopsAndHealthSaysWhy() throws Exception {
			publish();

			assertThat(GEMINI.awaitRequest(20)).isTrue();
			// Long enough for a redelivery (paced 4 s apart), or the retry backoff's first 5 s, to reach the stub.
			Thread.sleep(7_000);

			assertThat(GEMINI.requests()).hasSize(1);
			assertThat(CONTENT.results()).isEmpty();
			assertStoppedWithTheTaskInTheQueue();
			JsonNode components = health();
			assertThat(components.path("gemini").path("status").asString()).isEqualTo("DOWN");
			assertThat(components.path("gemini").path("details").path("reason").asString())
					.contains("API key rejected, HTTP 401 UNAUTHENTICATED")
					.contains("restart the worker")
					.doesNotContain(GEMINI.requests().getFirst().apiKeyHeader());
			assertThat(components.path("consumer").path("details").path("reason").asString())
					.isEqualTo(components.path("gemini").path("details").path("reason").asString());
		}
	}

	/** {@code CONTENT_URI} names spatial, which has no {@code /internal/lease}: its own 404 answers. */
	@Nested
	class ContentUriPointsElsewhere extends Scenario {

		private static final RabbitMQContainer BROKER = RabbitContainers.started();

		private static final GeminiStub GEMINI = new GeminiStub();

		private static final SpatialStub NOT_CONTENT = new SpatialStub();

		@DynamicPropertySource
		static void services(DynamicPropertyRegistry registry) {
			RabbitContainers.register(registry, BROKER);
			registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
			registry.add("trippie.content.uri", NOT_CONTENT::baseUrl);
			registry.add("trippie.spatial.uri", NOT_CONTENT::baseUrl);
		}

		@AfterAll
		static void stopStubs() {
			GEMINI.close();
			NOT_CONTENT.close();
		}

		@Test
		void theTaskStaysInTheQueueBeforeAnythingIsPaidOrWritten() throws Exception {
			publish();

			awaitStop();

			assertThat(GEMINI.requests()).isEmpty();
			assertThat(NOT_CONTENT.paths()).containsExactly("/internal/lease");
			assertStoppedWithTheTaskInTheQueue();
			JsonNode components = health();
			assertThat(components.path("gemini").path("status").asString()).isEqualTo("UP");
			assertThat(components.path("consumer").path("details").path("reason").asString())
					.isEqualTo("content answered HTTP 404 to the lease check; point CONTENT_URI at content and restart the worker");
		}
	}

	/** Something that is not spatial answers a plain 404, without spatial's problem body. */
	@Nested
	class SpatialUriPointsElsewhere extends Scenario {

		private static final RabbitMQContainer BROKER = RabbitContainers.started();

		private static final GeminiStub GEMINI = new GeminiStub();

		private static final ContentStub CONTENT = new ContentStub();

		private static final SpatialStub NOT_SPATIAL = new SpatialStub();

		@DynamicPropertySource
		static void services(DynamicPropertyRegistry registry) {
			RabbitContainers.register(registry, BROKER);
			registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
			registry.add("trippie.content.uri", CONTENT::baseUrl);
			registry.add("trippie.spatial.uri", NOT_SPATIAL::baseUrl);
			CONTENT.leased("node/1", TOKEN);
			NOT_SPATIAL.unavailable(404);
		}

		@AfterAll
		static void stopStubs() {
			GEMINI.close();
			CONTENT.close();
			NOT_SPATIAL.close();
		}

		@Test
		void theTaskStaysInTheQueueBeforeAnythingIsPaidOrWritten() throws Exception {
			publish();

			awaitStop();

			assertThat(GEMINI.requests()).isEmpty();
			assertThat(NOT_SPATIAL.paths()).containsExactly("/locations/node/1");
			assertThat(CONTENT.results()).isEmpty();
			assertThat(CONTENT.row("node/1")).isEqualTo(new ContentStub.Row("PROCESSING", TOKEN));
			assertStoppedWithTheTaskInTheQueue();
			JsonNode components = health();
			assertThat(components.path("gemini").path("status").asString()).isEqualTo("UP");
			assertThat(components.path("consumer").path("details").path("reason").asString())
					.isEqualTo("spatial answered HTTP 404 to the detail of node/1, not spatial's answer to an unknown POI;"
							+ " point SPATIAL_URI at spatial and restart the worker");
		}
	}

	/**
	 * Lease check and detail answer, the provider is paid, and only the write-back finds something
	 * that is not content: the text is not kept past the stop (review M4-11 round 2, LOW-5).
	 */
	@Nested
	class WriteBackRefused extends Scenario {

		private static final RabbitMQContainer BROKER = RabbitContainers.started();

		private static final GeminiStub GEMINI = new GeminiStub();

		private static final ContentStub CONTENT = new ContentStub();

		private static final SpatialStub SPATIAL = new SpatialStub();

		@DynamicPropertySource
		static void services(DynamicPropertyRegistry registry) {
			RabbitContainers.register(registry, BROKER);
			registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
			registry.add("trippie.content.uri", CONTENT::baseUrl);
			registry.add("trippie.spatial.uri", SPATIAL::baseUrl);
			CONTENT.leased("node/1", TOKEN);
			CONTENT.refuseResults(404);
			SPATIAL.poi("node/1", "Ratusz", "architecture");
			GEMINI.respondJson(200, GeminiStub.ok("Przewodnik po Ratuszu"));
		}

		@AfterAll
		static void stopStubs() {
			GEMINI.close();
			CONTENT.close();
			SPATIAL.close();
		}

		@Test
		void theTaskStaysInTheQueueAfterOnePaidCallAndNoFailureIsRecorded() throws Exception {
			publish();

			awaitStop();

			assertThat(GEMINI.requests()).hasSize(1);
			assertThat(CONTENT.results()).singleElement()
					.satisfies(result -> assertThat(result.path("status").asString()).isEqualTo("COMPLETED"));
			assertThat(CONTENT.row("node/1")).isEqualTo(new ContentStub.Row("PROCESSING", TOKEN));
			assertStoppedWithTheTaskInTheQueue();
			JsonNode components = health();
			assertThat(components.path("gemini").path("status").asString()).isEqualTo("UP");
			assertThat(components.path("consumer").path("details").path("reason").asString())
					.isEqualTo("content answered HTTP 404 to the completion; point CONTENT_URI at content and restart the worker");
		}
	}

	/**
	 * {@code SPATIAL_URI} names spatial under a wrong path prefix: spatial's own problem 404 for an
	 * unknown route, whose detail is not the unknown POI's (user decision 2026-11-12).
	 */
	@Nested
	class SpatialUriHasAWrongPathPrefix extends Scenario {

		private static final RabbitMQContainer BROKER = RabbitContainers.started();

		private static final GeminiStub GEMINI = new GeminiStub();

		private static final ContentStub CONTENT = new ContentStub();

		private static final SpatialStub SPATIAL = new SpatialStub();

		@DynamicPropertySource
		static void services(DynamicPropertyRegistry registry) {
			RabbitContainers.register(registry, BROKER);
			registry.add("trippie.gemini.base-url", GEMINI::baseUrl);
			registry.add("trippie.content.uri", CONTENT::baseUrl);
			registry.add("trippie.spatial.uri", () -> SPATIAL.baseUrl() + "/api");
			CONTENT.leased("node/1", TOKEN);
			SPATIAL.poi("node/1", "Ratusz", "architecture");
		}

		@AfterAll
		static void stopStubs() {
			GEMINI.close();
			CONTENT.close();
			SPATIAL.close();
		}

		@Test
		void theTaskStaysInTheQueueBeforeAnythingIsPaidOrWritten() throws Exception {
			publish();

			awaitStop();

			assertThat(GEMINI.requests()).isEmpty();
			assertThat(SPATIAL.paths()).containsExactly("/api/locations/node/1");
			assertThat(CONTENT.results()).isEmpty();
			assertStoppedWithTheTaskInTheQueue();
			assertThat(health().path("consumer").path("details").path("reason").asString())
					.startsWith("spatial answered HTTP 404 to the detail of node/1, not spatial's answer to an unknown POI");
		}
	}

	/** The beans of the scenario's own context: the enclosing instance is not injected from it. */
	abstract static class Scenario {

		@Value("${local.server.port}")
		private int port;

		@Autowired
		private RabbitTemplate rabbit;

		@Autowired
		private RabbitAdmin admin;

		@Autowired
		private RabbitListenerEndpointRegistry listeners;

		void publish() {
			rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, MessageBuilder
					.withBody(TASK.getBytes(StandardCharsets.UTF_8))
					.setContentType(MessageProperties.CONTENT_TYPE_JSON)
					.build());
		}

		/** The stop, then a window in which a redelivery would show in the assertions that follow. */
		void awaitStop() throws InterruptedException {
			await().atMost(Duration.ofSeconds(10))
					.until(() -> !listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()
							&& admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount() == 1);
			Thread.sleep(2_000);
		}

		void assertStoppedWithTheTaskInTheQueue() {
			assertThat(admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount()).isZero();
			assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount()).isEqualTo(1);
			assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getConsumerCount()).isZero();
			assertThat(listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()).isFalse();
		}

		/** The components of {@code /health}, which answers 503 overall. */
		JsonNode health() throws Exception {
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(503);
			JsonNode components = JsonMapper.shared().readTree(response.body()).path("components");
			assertThat(components.path("rabbit").path("status").asString()).isEqualTo("UP");
			assertThat(components.path("consumer").path("status").asString()).isEqualTo("DOWN");
			return components;
		}
	}
}
