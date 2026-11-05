package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.ContentApplication;
import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.support.PublishBudget;
import com.laa66.content.support.RabbitContainers;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * The broker loses its definitions while content runs, and the redeclaration on the reconnect is
 * refused: the connection that failed to declare stays cached, and before the fix every later
 * publish met a missing exchange until that connection happened to drop, long after the broker
 * would have accepted the declaration. Refused configure permission is the deterministic stand-in
 * for a declaration that misses its RPC timeout.
 */
class TopologyLostAtRuntimeIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final String USER = "content";

	private static final String PASSWORD = "content";

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	@Test
	void publishingRecoversOnceTheBrokerAcceptsTheDeclarationAgain() throws Exception {
		rabbitmqctl("add_user", USER, PASSWORD);
		rabbitmqctl("set_permissions", "-p", "/", USER, ".*", ".*", ".*");

		try (ConfigurableApplicationContext context = boot()) {
			EventPublisher publisher = context.getBean(EventPublisher.class);
			RabbitTemplate rabbit = context.getBean(RabbitTemplate.class);
			publisher.publish(TASK);
			assertThat(rabbit.receive(GenerationTopology.QUEUE, 5_000)).isNotNull();

			deleteTheTopology();
			rabbitmqctl("set_permissions", "-p", "/", USER, "^$", ".*", ".*");
			context.getBean(CachingConnectionFactory.class).resetConnection();

			// The reconnect opens, its declaration is refused, and the connection stays cached.
			PublishBudget.assertFailsWithinBudget(publisher, TASK);

			rabbitmqctl("set_permissions", "-p", "/", USER, ".*", ".*", ".*");

			// Still the cached connection: the broker would declare now, but nobody asks it to. This is
			// the publish that resets the connection.
			assertThat(PublishBudget.assertFailsWithinBudget(publisher, TASK)).hasMessageContaining("NOT_FOUND");

			for (int attempt = 0; attempt < 5; attempt++) {
				publisher.publish(TASK);
			}
			for (int delivered = 0; delivered < 5; delivered++) {
				Message received = rabbit.receive(GenerationTopology.QUEUE, 5_000);
				assertThat(received).as("nothing arrived in the work queue").isNotNull();
				assertThat(JsonMapper.shared().readValue(new String(received.getBody(), StandardCharsets.UTF_8),
						GenerateContentTask.class)).isEqualTo(TASK);
			}
		}
	}

	private static ConfigurableApplicationContext boot() {
		return new SpringApplicationBuilder(ContentApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.rabbitmq.host=" + BROKER.getHost(),
						"--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
						"--spring.rabbitmq.username=" + USER,
						"--spring.rabbitmq.password=" + PASSWORD);
	}

	private static void deleteTheTopology() throws Exception {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(BROKER.getHost());
		factory.setPort(BROKER.getAmqpPort());
		factory.setUsername(BROKER.getAdminUsername());
		factory.setPassword(BROKER.getAdminPassword());
		try (com.rabbitmq.client.Connection connection = factory.newConnection();
				Channel channel = connection.createChannel()) {
			channel.queueDelete(GenerationTopology.QUEUE);
			channel.queueDelete(GenerationTopology.DEAD_LETTER_QUEUE);
			channel.exchangeDelete(GenerationTopology.EXCHANGE);
			channel.exchangeDelete(GenerationTopology.DEAD_LETTER_EXCHANGE);
		}
	}

	private static void rabbitmqctl(String... command) throws Exception {
		String[] line = new String[command.length + 1];
		line[0] = "rabbitmqctl";
		System.arraycopy(command, 0, line, 1, command.length);
		assertThat(BROKER.execInContainer(line).getExitCode()).as(String.join(" ", line)).isZero();
	}
}
