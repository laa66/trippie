package com.laa66.worker.support;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The image infra/docker-compose.yml runs ({@code WorkerComposeTest} holds the two together). Each IT
 * class owns its broker, because what it asserts depends on the broker's history.
 */
public final class RabbitContainers {

	public static final String IMAGE = "rabbitmq:4.0-management";

	/** Same measured reason as content's: the default 60 s is too short on a loaded machine. */
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

	private RabbitContainers() {
	}

	public static RabbitMQContainer started() {
		RabbitMQContainer broker = new RabbitMQContainer(IMAGE).withStartupTimeout(STARTUP_TIMEOUT);
		broker.start();
		return broker;
	}

	public static void register(DynamicPropertyRegistry registry, RabbitMQContainer broker) {
		registry.add("spring.rabbitmq.host", broker::getHost);
		registry.add("spring.rabbitmq.port", broker::getAmqpPort);
		registry.add("spring.rabbitmq.username", broker::getAdminUsername);
		registry.add("spring.rabbitmq.password", broker::getAdminPassword);
	}
}
