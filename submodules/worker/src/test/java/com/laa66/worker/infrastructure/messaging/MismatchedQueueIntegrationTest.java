package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.WorkerApplication;
import com.laa66.worker.support.RabbitContainers;

/**
 * A work queue that exists without its dead-letter exchange (declared by hand, or by an older
 * build) cannot be fixed by redeclaring: arguments are immutable. Consuming from it would turn every
 * rejected task into a lost one, so the worker refuses to start instead.
 */
class MismatchedQueueIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	@AfterAll
	static void stop() {
		BROKER.stop();
	}

	@Test
	void aWorkQueueWithoutItsDeadLetterExchangeStopsTheBoot() {
		CachingConnectionFactory connectionFactory = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
		connectionFactory.setUsername(BROKER.getAdminUsername());
		connectionFactory.setPassword(BROKER.getAdminPassword());
		try {
			new RabbitAdmin(connectionFactory).declareQueue(new Queue(GenerationTopology.QUEUE, true));
		}
		finally {
			connectionFactory.destroy();
		}

		assertThatThrownBy(() -> new SpringApplicationBuilder(WorkerApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.rabbitmq.host=" + BROKER.getHost(),
						"--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
						"--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
						"--spring.rabbitmq.password=" + BROKER.getAdminPassword())
				.close())
				.satisfies(failure -> assertThat(rootMessage(failure)).contains("PRECONDITION_FAILED"));
	}

	private static String rootMessage(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			messages.append(cause).append('\n');
		}
		return messages.toString();
	}
}
