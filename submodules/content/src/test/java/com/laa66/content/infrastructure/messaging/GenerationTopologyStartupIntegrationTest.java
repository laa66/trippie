package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.RabbitContainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a boot alone leaves on a broker nothing has touched. The broker is read through
 * {@code rabbitmqctl}, never through the application's connection: opening that connection would
 * itself trigger the declaration this class exists to prove, and no publish happens here for the
 * same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GenerationTopologyStartupIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
	}

	@Test
	void bothExchangesAreDeclaredDurableAtBoot() throws Exception {
		JsonNode exchanges = rabbitmqctl("list_exchanges", "name", "type", "durable");

		assertThat(find(exchanges, "trippie.content").path("type").asString()).isEqualTo("direct");
		assertThat(find(exchanges, "trippie.content").path("durable").asBoolean()).isTrue();
		assertThat(find(exchanges, "trippie.content.dlx").path("type").asString()).isEqualTo("direct");
		assertThat(find(exchanges, "trippie.content.dlx").path("durable").asBoolean()).isTrue();
	}

	@Test
	void theWorkQueueIsBornWithItsDeadLetterExchange() throws Exception {
		JsonNode queue = find(rabbitmqctl("list_queues", "name", "durable", "arguments"), "content.generate");

		assertThat(queue.path("durable").asBoolean()).isTrue();
		assertThat(arguments(queue)).containsEntry("x-dead-letter-exchange", "trippie.content.dlx");
	}

	@Test
	void theDeadLetterQueueIsDurable() throws Exception {
		JsonNode queue = find(rabbitmqctl("list_queues", "name", "durable", "arguments"), "content.generate.dlq");

		assertThat(queue.path("durable").asBoolean()).isTrue();
		assertThat(arguments(queue)).as("a DLQ that dead-letters would hide its own messages")
				.doesNotContainKey("x-dead-letter-exchange");
	}

	@Test
	void bothQueuesAreBoundOnTheRoutingKey() throws Exception {
		List<String> bindings = new ArrayList<>();
		rabbitmqctl("list_bindings", "source_name", "destination_name", "routing_key").forEach(
				binding -> bindings.add(binding.path("source_name").asString() + " -> "
						+ binding.path("destination_name").asString() + " : " + binding.path("routing_key").asString()));

		assertThat(bindings).contains(
				"trippie.content -> content.generate : content.generate",
				"trippie.content.dlx -> content.generate.dlq : content.generate");
	}

	/** {@code rabbitmqctl} prints arguments as {@code [name, type, value]} triples. */
	private static Map<String, String> arguments(JsonNode queue) {
		Map<String, String> arguments = new HashMap<>();
		queue.path("arguments").forEach(argument -> arguments.put(argument.get(0).asString(), argument.get(2).asString()));
		return arguments;
	}

	private static JsonNode find(JsonNode rows, String name) {
		for (JsonNode row : rows) {
			if (name.equals(row.path("name").asString())) {
				return row;
			}
		}
		throw new AssertionError(name + " is not declared on the broker: " + rows);
	}

	private static JsonNode rabbitmqctl(String command, String... columns) throws IOException, InterruptedException {
		List<String> line = new ArrayList<>(List.of("rabbitmqctl", "--quiet", "--formatter", "json", command));
		line.addAll(List.of(columns));
		String stdout = BROKER.execInContainer(line.toArray(String[]::new)).getStdout();
		return JsonMapper.shared().readTree(stdout);
	}
}
