package com.laa66.worker.infrastructure.config;

import java.util.Map;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.infrastructure.messaging.GenerationTaskListener;
import com.laa66.worker.infrastructure.messaging.GenerationTopology;
import com.laa66.worker.infrastructure.messaging.TaskRetryPolicy;

/**
 * The module's wiring point for the broker: the generation topology, the listener and the one
 * container it runs in.
 */
@Configuration
public class MessagingConfig {

	/**
	 * The worker is a serial pipeline, not a pool: one consumer, one message in flight. Under 15 RPM
	 * a second consumer would only queue behind the same pacer while holding a second message (and
	 * its lease clock) hostage. A constant for the reason {@code ProviderCallPacer} gives.
	 */
	public static final int CONSUMER_CONCURRENCY = 1;

	/**
	 * One unacknowledged message at a time: the queue keeps the rest, where a restart or a second
	 * worker can still see them, instead of in this consumer's buffer.
	 */
	public static final int PREFETCH = 1;

	/** The same declarables as content's {@code MessagingConfig}, argument for argument. */
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
	 * Boot's configurer applies the connection-level settings and the virtual-thread executor; the
	 * delivery policy is set after it, so no {@code spring.rabbitmq.listener.*} property can change
	 * it. The retry interceptor wraps only the listener call, and every failure inside it ends in the
	 * recoverer's reject-without-requeue. {@code defaultRequeueRejected=false} is for what fails
	 * outside it, in the container's own steps before the listener (splitting a body that carries
	 * Spring's batch header): rejected without requeue, so dead-lettered, where Boot's default would
	 * requeue it straight back to this consumer in a hot loop. It is NOT a fallback for a missing
	 * recoverer (see {@link TaskRetryPolicy#interceptor()}). A message rejected while the container
	 * stops is requeued by Spring AMQP whatever this says. {@code mismatchedQueuesFatal}
	 * stops the boot when the broker holds the queue with other arguments (no dead-letter exchange),
	 * where a rejected task would vanish.
	 */
	@Bean
	SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
			SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		configurer.configure(factory, connectionFactory);
		factory.setConcurrentConsumers(CONSUMER_CONCURRENCY);
		factory.setMaxConcurrentConsumers(CONSUMER_CONCURRENCY);
		factory.setPrefetchCount(PREFETCH);
		factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
		factory.setDefaultRequeueRejected(false);
		factory.setMismatchedQueuesFatal(true);
		factory.setAdviceChain(TaskRetryPolicy.interceptor());
		return factory;
	}

	@Bean
	GenerationTaskListener generationTaskListener(TaskConsumer taskConsumer) {
		return new GenerationTaskListener(taskConsumer);
	}
}
