package com.laa66.content.support;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Every broker IT runs the exact image infra/docker-compose.yml runs; {@code
 * GenerationTopologyTest#theItBrokerImageIsTheOneComposeRuns} holds the two together.
 *
 * <p>Each IT class owns its container and they all build their own Spring context, because what they
 * assert depends on the broker's history: a fresh broker no publish has touched, one that is
 * stopped, one under a memory alarm.
 */
public final class RabbitContainers {

	public static final String IMAGE = "rabbitmq:4.0-management";

	/**
	 * The default 60 s is less than this broker needs to boot on a loaded machine (measured: 66 busy
	 * threads on 22 cores), and a timeout in a static initializer fails the whole class at once.
	 */
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
