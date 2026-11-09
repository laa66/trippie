package com.laa66.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.laa66.worker.infrastructure.messaging.GenerationTaskListener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * No key file: the worker still boots, never starts the listener, and says why on {@code /health}.
 * The broker half (the tasks stay in the queue) is {@code NoApiKeyIntegrationTest}; the contrast,
 * a started listener with a key, is {@code WorkerApplicationTests}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "trippie.gemini.api-key-file=")
class NoApiKeyStartupTest {

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@Test
	void theListenerIsNeverStarted() {
		MessageListenerContainer container = listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID);

		assertThat(container.isAutoStartup()).isFalse();
		assertThat(container.isRunning()).isFalse();
	}

	@Test
	void healthIsDownAndNamesTheReason() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(503);
		JsonNode gemini = JsonMapper.shared().readTree(response.body()).path("components").path("gemini");
		assertThat(gemini.path("status").asString()).isEqualTo("DOWN");
		assertThat(gemini.path("details").path("reason").asString()).isEqualTo("GEMINI_API_KEY_FILE is not set");
	}
}
