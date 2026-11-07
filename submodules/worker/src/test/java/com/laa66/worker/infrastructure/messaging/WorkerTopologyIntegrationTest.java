package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.support.RabbitContainers;

/**
 * A worker that boots against a broker nothing else has touched (compose does not order it after
 * content) declares the topology itself, dead-letter wiring included, and starts consuming.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerTopologyIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
	}

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@Test
	void onAFreshBrokerTheWorkQueueIsDeclaredWithItsDeadLetterExchange() throws Exception {
		String queues = BROKER.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "arguments").getStdout();

		assertThat(queues).containsPattern("(?m)^content\\.generate\\s.*x-dead-letter-exchange.*trippie\\.content\\.dlx");
		assertThat(queues).containsPattern("(?m)^content\\.generate\\.dlq\\s");
		String bindings = BROKER.execInContainer("rabbitmqctl", "-q", "list_bindings",
				"source_name", "destination_name", "routing_key").getStdout();
		assertThat(bindings)
				.containsPattern("(?m)^trippie\\.content\\s+content\\.generate\\s+content\\.generate$")
				.containsPattern("(?m)^trippie\\.content\\.dlx\\s+content\\.generate\\.dlq\\s+content\\.generate$");
	}

	@Test
	void theListenerIsRunning() {
		assertThat(listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()).isTrue();
	}

	@Test
	void healthIsUpWithTheBrokerReachable() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}
}
