package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
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
import com.rabbitmq.client.ShutdownSignalException;

import tools.jackson.databind.json.JsonMapper;

/**
 * A frozen broker ({@code docker pause}): the socket stays open and nothing answers, and neither a
 * refused connect nor a reset ever tells the client. Every step of a publish is then bounded only by
 * its own timeout, so each path through the client gets a test: an open connection, a connection
 * that has to be reopened, and a boot.
 *
 * <p>Each test boots its own context: the boot is one of the things under test, and the reconnect
 * case needs a different heartbeat.
 */
class FrozenBrokerIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final Duration BOOT_BUDGET = Duration.ofSeconds(30);

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	/**
	 * The first publish spends the confirm wait, every later one waits on {@code channel.open}. Without
	 * {@code channel-rpc-timeout} those hang until the heartbeat gives up on the connection (measured:
	 * 130 s).
	 */
	@Test
	void everyPublishOnAnOpenConnectionFailsInsideTheBudgetAndPublishingWorksAfterTheThaw() {
		try (ConfigurableApplicationContext context = boot()) {
			EventPublisher publisher = context.getBean(EventPublisher.class);
			publisher.publish(TASK);

			freeze();
			try {
				for (int attempt = 0; attempt < 3; attempt++) {
					PublishBudget.assertFailsWithinBudget(publisher, TASK);
				}
			}
			finally {
				thaw();
			}

			publisher.publish(TASK);
		}
	}

	/**
	 * Once the heartbeat has closed the connection, a publish opens a new one: the connect succeeds
	 * and the AMQP handshake is what waits. A 1 s heartbeat gets there in seconds instead of two
	 * minutes.
	 */
	@Test
	void aPublishThatHasToReconnectToAFrozenBrokerFailsInsideTheBudget() throws Exception {
		try (ConfigurableApplicationContext context = boot("--spring.rabbitmq.requested-heartbeat=1s")) {
			EventPublisher publisher = context.getBean(EventPublisher.class);
			publisher.publish(TASK);
			CountDownLatch connectionLost = new CountDownLatch(1);
			context.getBean(CachingConnectionFactory.class).addConnectionListener(new ConnectionListener() {

				@Override
				public void onCreate(Connection connection) {
				}

				@Override
				public void onShutDown(ShutdownSignalException signal) {
					connectionLost.countDown();
				}
			});

			freeze();
			try {
				assertThat(connectionLost.await(30, TimeUnit.SECONDS)).as("the heartbeat never closed the connection").isTrue();
				for (int attempt = 0; attempt < 2; attempt++) {
					PublishBudget.assertFailsWithinBudget(publisher, TASK);
				}
			}
			finally {
				thaw();
			}

			publisher.publish(TASK);
		}
	}

	/**
	 * The boot-time declaration times out, the boot goes on, and the topology arrives with the first
	 * connection after the thaw, before that connection's first publish.
	 */
	@Test
	void aBootAgainstAFrozenBrokerComesUpAndDeclaresTheTopologyOnceTheBrokerAnswers() throws Exception {
		deleteTheTopology();

		freeze();
		ConfigurableApplicationContext context;
		try {
			context = assertTimeoutPreemptively(BOOT_BUDGET, () -> boot());
			PublishBudget.assertFailsWithinBudget(context.getBean(EventPublisher.class), TASK);
		}
		finally {
			thaw();
		}

		try (context) {
			context.getBean(EventPublisher.class).publish(TASK);

			Message received = context.getBean(RabbitTemplate.class).receive(GenerationTopology.QUEUE, 5_000);
			assertThat(received).as("nothing arrived in the work queue").isNotNull();
			assertThat(JsonMapper.shared().readValue(new String(received.getBody(), StandardCharsets.UTF_8),
					GenerateContentTask.class)).isEqualTo(TASK);
		}
	}

	private static ConfigurableApplicationContext boot(String... extraArgs) {
		List<String> args = new ArrayList<>(List.of(
				"--spring.rabbitmq.host=" + BROKER.getHost(),
				"--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
				"--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
				"--spring.rabbitmq.password=" + BROKER.getAdminPassword()));
		args.addAll(List.of(extraArgs));
		return new SpringApplicationBuilder(ContentApplication.class)
				.web(WebApplicationType.NONE)
				.run(args.toArray(String[]::new));
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

	private static void freeze() {
		BROKER.getDockerClient().pauseContainerCmd(BROKER.getContainerId()).exec();
	}

	private static void thaw() {
		BROKER.getDockerClient().unpauseContainerCmd(BROKER.getContainerId()).exec();
	}
}
