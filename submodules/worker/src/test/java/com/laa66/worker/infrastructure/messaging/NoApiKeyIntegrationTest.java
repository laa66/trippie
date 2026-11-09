package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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

import com.laa66.worker.support.RabbitContainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A worker booted with an empty key file on a live broker: the topology is still declared, a
 * published task is neither consumed nor dead-lettered, and {@code /health} is DOWN because of the
 * key alone, with the broker UP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoApiKeyIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) throws Exception {
		RabbitContainers.register(registry, BROKER);
		java.nio.file.Path empty = java.nio.file.Files.createTempFile("gemini", ".key");
		empty.toFile().deleteOnExit();
		registry.add("trippie.gemini.api-key-file", empty::toString);
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
	void aTaskWaitsInTheQueueAndNothingIsDeadLettered() throws Exception {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, MessageBuilder
				.withBody("{\"key\":{\"osmId\":\"node/1\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}"
						.getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.build());
		// Long enough for a running consumer to have taken it.
		Thread.sleep(3_000);

		assertThat(listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()).isFalse();
		assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount()).isEqualTo(1);
		assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getConsumerCount()).isZero();
		assertThat(admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount()).isZero();
	}

	@Test
	void healthIsDownBecauseOfTheKeyWithTheBrokerUp() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(503);
		JsonNode components = JsonMapper.shared().readTree(response.body()).path("components");
		assertThat(components.path("rabbit").path("status").asString()).isEqualTo("UP");
		assertThat(components.path("gemini").path("status").asString()).isEqualTo("DOWN");
		assertThat(components.path("gemini").path("details").path("reason").asString()).contains("is empty");
	}
}
