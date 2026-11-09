package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.RabbitContainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review M4-10, MEDIUM-1: the provider refuses the key at runtime. The task goes back to the queue
 * and stays there, nothing is dead-lettered, the listener stops, and {@code /health} is DOWN with the
 * reason, as when the key file is missing ({@code NoApiKeyIntegrationTest}).
 *
 * <p>The production {@code TaskConsumer} does not call the provider before M4-11, so a stand-in
 * calls the shipped {@code TextGenerator} bean (paced adapter on the shipped request factory) once per
 * task. Everything between the broker and that call is production wiring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeyRejectedIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final GeminiStub STUB = new GeminiStub();

	@DynamicPropertySource
	static void brokerAndProvider(DynamicPropertyRegistry registry) throws Exception {
		RabbitContainers.register(registry, BROKER);
		registry.add("trippie.gemini.base-url", STUB::baseUrl);
		String key = Files.readString(Path.of("src/test/resources/fake-gemini.key")).strip();
		STUB.respondJson(401, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\",\"message\":\"key " + key
				+ " is not valid\"}}");
	}

	@AfterAll
	static void stopStub() {
		STUB.close();
	}

	@TestConfiguration
	static class ProviderCalling {

		@Bean
		@Primary
		TaskConsumer providerCallingConsumer(TextGenerator textGenerator) {
			return task -> textGenerator.generate(new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, task.key().lang()));
		}
	}

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private RabbitAdmin admin;

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@Test
	void theTaskStaysInTheQueueTheListenerStopsAndHealthSaysWhy() throws Exception {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, MessageBuilder
				.withBody("{\"key\":{\"osmId\":\"node/1\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}"
						.getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.build());

		assertThat(STUB.awaitRequest(20)).isTrue();
		// Long enough for a redelivery (paced 4 s apart), or the retry backoff's first 5 s, to reach the stub.
		Thread.sleep(7_000);

		assertThat(STUB.requests()).hasSize(1);
		assertThat(admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount()).isZero();
		assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount()).isEqualTo(1);
		assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getConsumerCount()).isZero();
		assertThat(listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()).isFalse();

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(503);
		JsonNode components = JsonMapper.shared().readTree(response.body()).path("components");
		assertThat(components.path("rabbit").path("status").asString()).isEqualTo("UP");
		assertThat(components.path("gemini").path("status").asString()).isEqualTo("DOWN");
		assertThat(components.path("gemini").path("details").path("reason").asString())
				.contains("API key rejected, HTTP 401 UNAUTHENTICATED")
				.contains("restart the worker")
				.doesNotContain(STUB.requests().getFirst().apiKeyHeader());
	}
}
