package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import com.laa66.content.support.RabbitContainers;

import tools.jackson.databind.json.JsonMapper;

/**
 * The boot-time declaration fails on a connection that did open: here the user may not configure
 * anything, which is the deterministic stand-in for a declaration that misses its RPC timeout on a
 * slow broker. Once the broker would accept it, the next publish must declare and land, which needs
 * a new connection: the one from the boot never declares again.
 */
class DeclarationRefusedAtBootIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final String USER = "content";

	private static final String PASSWORD = "content";

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	@Test
	void aDeclarationRefusedOnAnOpenConnectionIsRepeatedOnTheNextOne() throws Exception {
		rabbitmqctl("add_user", USER, PASSWORD);
		rabbitmqctl("set_permissions", "-p", "/", USER, "^$", ".*", ".*");

		try (ConfigurableApplicationContext context = boot()) {
			rabbitmqctl("set_permissions", "-p", "/", USER, ".*", ".*", ".*");

			context.getBean(EventPublisher.class).publish(TASK);

			Message received = context.getBean(RabbitTemplate.class).receive(GenerationTopology.QUEUE, 5_000);
			assertThat(received).as("nothing arrived in the work queue").isNotNull();
			assertThat(JsonMapper.shared().readValue(new String(received.getBody(), StandardCharsets.UTF_8),
					GenerateContentTask.class)).isEqualTo(TASK);
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

	private static void rabbitmqctl(String... command) throws Exception {
		String[] line = new String[command.length + 1];
		line[0] = "rabbitmqctl";
		System.arraycopy(command, 0, line, 1, command.length);
		assertThat(BROKER.execInContainer(line).getExitCode()).as(String.join(" ", line)).isZero();
	}
}
