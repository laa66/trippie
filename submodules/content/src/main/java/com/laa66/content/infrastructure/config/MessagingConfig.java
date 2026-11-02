package com.laa66.content.infrastructure.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.infrastructure.messaging.GenerationTopology;
import com.laa66.content.infrastructure.messaging.RabbitEventPublisher;
import com.laa66.content.infrastructure.messaging.TopologyDeclarer;

/**
 * The module's wiring point for the broker: the generation topology and the publisher.
 */
@Configuration
public class MessagingConfig {

	/**
	 * How long a publish waits for the broker's verdict once the message is written, on a request
	 * thread once M4-08 exists. A constant for the reason given on
	 * {@link ContentConfig#SPATIAL_TIMEOUT}. The steps before that wait are bounded separately:
	 * {@code connection-timeout} and {@code channel-rpc-timeout} in application.yaml.
	 */
	public static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	Declarables generationTopology() {
		DirectExchange exchange = new DirectExchange(GenerationTopology.EXCHANGE);
		DirectExchange deadLetterExchange = new DirectExchange(GenerationTopology.DEAD_LETTER_EXCHANGE);
		Queue queue = QueueBuilder.durable(GenerationTopology.QUEUE)
				.withArguments(Map.of("x-dead-letter-exchange", GenerationTopology.DEAD_LETTER_EXCHANGE))
				.build();
		Queue deadLetterQueue = QueueBuilder.durable(GenerationTopology.DEAD_LETTER_QUEUE).build();
		Binding binding = BindingBuilder.bind(queue).to(exchange).with(GenerationTopology.ROUTING_KEY);
		Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
				.with(GenerationTopology.ROUTING_KEY);
		return new Declarables(exchange, deadLetterExchange, queue, deadLetterQueue, binding, deadLetterBinding);
	}

	/**
	 * Without the admin's default retry a rejected declaration costs one round trip. The retry adds
	 * 15 s of backoff to it, on a request thread when a publish opens the connection, and it exists
	 * for a race on auto-delete queues this topology does not have.
	 */
	@Bean
	RabbitAdmin amqpAdmin(ConnectionFactory connectionFactory) {
		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		admin.setRetryTemplate(null);
		return admin;
	}

	@Bean
	ApplicationRunner declareGenerationTopology(AmqpAdmin admin, ConnectionFactory connectionFactory) {
		return new TopologyDeclarer(admin, connectionFactory);
	}

	@Bean
	EventPublisher eventPublisher(RabbitOperations rabbitOperations) {
		return new RabbitEventPublisher(rabbitOperations, CONFIRM_TIMEOUT);
	}
}
