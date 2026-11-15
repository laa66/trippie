package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.WorkerApplication;
import com.laa66.worker.support.ContentStub;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.RabbitContainers;
import com.laa66.worker.support.SpatialStub;

/**
 * Review M4-09, LOW-1: closing the worker while a delivery sits in its retry backoff is not a failure
 * of the task. The task stays in the work queue, nothing is dead-lettered or written back, and the
 * provider is not called again.
 *
 * <p>Two shapes, because the container ends them differently. A backoff that ends inside the
 * container's 5 s shutdown wait would run the next attempt; the listener refuses to start it. A
 * backoff longer than the wait is interrupted, and the recoverer sees the interrupt.
 *
 * <p>Each test boots its own worker and closes it; the counts are read on a connection of the
 * test's own, after the close.
 */
class ShutdownDuringBackoffIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final GeminiStub GEMINI = new GeminiStub();

	private static final ContentStub CONTENT = new ContentStub();

	private static final SpatialStub SPATIAL = new SpatialStub();

	private CachingConnectionFactory connectionFactory;

	private RabbitAdmin admin;

	@BeforeEach
	void ownConnection() {
		connectionFactory = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
		connectionFactory.setUsername(BROKER.getAdminUsername());
		connectionFactory.setPassword(BROKER.getAdminPassword());
		admin = new RabbitAdmin(connectionFactory);
		CONTENT.reset();
		CONTENT.leased("node/1", Instant.parse("2026-11-09T12:00:00.123456Z"));
		SPATIAL.poi("node/1", "Ratusz", "architecture");
		GEMINI.respondJson(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}");
	}

	@AfterEach
	void closeConnection() {
		admin.purgeQueue(GenerationTopology.QUEUE);
		admin.purgeQueue(GenerationTopology.DEAD_LETTER_QUEUE);
		connectionFactory.destroy();
	}

	@AfterAll
	static void stopStubs() {
		GEMINI.close();
		CONTENT.close();
		SPATIAL.close();
	}

	/** Measured in the M4-09 review: this close used to run the second, paid attempt. */
	@Test
	void aCloseInTheFiveSecondBackoffStartsNoSecondAttempt() throws Exception {
		int calls = GEMINI.requests().size();
		ConfigurableApplicationContext worker = startWorker();
		new RabbitTemplate(connectionFactory).send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY,
				GenerationIntegrationTest.message("node/1"));
		await().atMost(Duration.ofSeconds(20)).until(() -> GEMINI.requests().size() == calls + 1);
		Thread.sleep(1_500);

		worker.close();

		assertKeptInTheQueue(calls + 1);
	}

	@Test
	void aCloseInTheFifteenSecondBackoffInterruptsItAndStartsNoThirdAttempt() throws Exception {
		int calls = GEMINI.requests().size();
		ConfigurableApplicationContext worker = startWorker();
		new RabbitTemplate(connectionFactory).send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY,
				GenerationIntegrationTest.message("node/1"));
		await().atMost(Duration.ofSeconds(30)).until(() -> GEMINI.requests().size() == calls + 2);
		Thread.sleep(1_500);

		worker.close();

		assertKeptInTheQueue(calls + 2);
	}

	private void assertKeptInTheQueue(int providerCalls) throws InterruptedException {
		// The broker settles the nack or the closed channel a moment after the close returns.
		await().atMost(Duration.ofSeconds(10))
				.until(() -> admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount() == 1);
		Thread.sleep(1_000);
		assertThat(admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount()).isEqualTo(1);
		assertThat(admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount()).isZero();
		assertThat(GEMINI.requests()).hasSize(providerCalls);
		assertThat(CONTENT.results()).isEmpty();
	}

	/** Command-line arguments: the test profile's file would override default properties. */
	private static ConfigurableApplicationContext startWorker() {
		return new SpringApplicationBuilder(WorkerApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.rabbitmq.host=" + BROKER.getHost(),
						"--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
						"--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
						"--spring.rabbitmq.password=" + BROKER.getAdminPassword(),
						"--trippie.gemini.base-url=" + GEMINI.baseUrl(),
						"--trippie.content.uri=" + CONTENT.baseUrl(),
						"--trippie.spatial.uri=" + SPATIAL.baseUrl());
	}
}
