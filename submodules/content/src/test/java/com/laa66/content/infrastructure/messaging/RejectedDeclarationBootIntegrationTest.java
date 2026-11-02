package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.ContentApplication;
import com.laa66.content.support.RabbitContainers;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;

/** What must stop a boot, on a real broker: neither of these heals by waiting. */
class RejectedDeclarationBootIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final Duration FAILS_FAST = Duration.ofSeconds(10);

	@Test
	void aQueueDeclaredWithOtherArgumentsStopsTheBootWithoutTheRetryBackoff() throws Exception {
		declareTheWorkQueueWithoutItsDeadLetterExchange();

		Instant start = Instant.now();
		assertThatThrownBy(() -> boot(BROKER.getAdminPassword()).close())
				.hasRootCauseInstanceOf(ShutdownSignalException.class)
				.hasStackTraceContaining("PRECONDITION_FAILED");
		assertThat(Duration.between(start, Instant.now())).isLessThan(FAILS_FAST);
	}

	@Test
	void refusedCredentialsStopTheBoot() {
		assertThatThrownBy(() -> boot("wrong").close()).isInstanceOf(AmqpAuthenticationException.class);
	}

	private static void declareTheWorkQueueWithoutItsDeadLetterExchange() throws Exception {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(BROKER.getHost());
		factory.setPort(BROKER.getAmqpPort());
		factory.setUsername(BROKER.getAdminUsername());
		factory.setPassword(BROKER.getAdminPassword());
		try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
			channel.queueDeclare(GenerationTopology.QUEUE, true, false, false, null);
		}
	}

	private static ConfigurableApplicationContext boot(String password) {
		return new SpringApplicationBuilder(ContentApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.rabbitmq.host=" + BROKER.getHost(),
						"--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
						"--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
						"--spring.rabbitmq.password=" + password);
	}
}
