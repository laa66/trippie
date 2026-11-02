package com.laa66.content.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.port.out.EventPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a task and waits for the broker's verdict, which takes two signals: a publisher confirm
 * says the broker processed the message, and only the absence of a mandatory return says a queue
 * got it. An unroutable message is confirmed with an ack, so an ack alone proves nothing.
 *
 * <p>Both need {@code publisher-confirm-type: correlated} and {@code publisher-returns: true} from
 * application.yaml. Without confirms no verdict arrives and every publish fails on the timeout.
 *
 * <p>The body is plain JSON with no {@code __TypeId__} header: the consumer is another module with
 * its own class, so the field names are the contract and a Java class name must not be. The mapper
 * is {@link JsonMapper#shared()} and not Boot's bean, so {@code spring.jackson.*} cannot reshape a
 * message another service reads. It writes the token as ISO-8601 text, which keeps every digit.
 */
public class RabbitEventPublisher implements EventPublisher {

	private final RabbitOperations rabbit;

	private final Duration confirmTimeout;

	public RabbitEventPublisher(RabbitOperations rabbit, Duration confirmTimeout) {
		this.rabbit = rabbit;
		this.confirmTimeout = confirmTimeout;
	}

	@Override
	public void publish(GenerateContentTask task) {
		Objects.requireNonNull(task, "task");

		CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
		CorrelationData.Confirm confirm;
		try {
			rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, toMessage(task), correlation);
			confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (AmqpException | ExecutionException ex) {
			throw new EventPublicationException("broker refused the publish", ex);
		}
		catch (TimeoutException ex) {
			throw new EventPublicationException("no broker confirm within " + confirmTimeout, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EventPublicationException("interrupted waiting for the broker confirm", ex);
		}

		if (!confirm.ack()) {
			throw new EventPublicationException("broker nacked the publish: " + confirm.reason());
		}
		ReturnedMessage returned = correlation.getReturned();
		if (returned != null) {
			throw new EventPublicationException("no queue took the message: " + returned.getReplyText());
		}
	}

	private static Message toMessage(GenerateContentTask task) {
		return MessageBuilder.withBody(JsonMapper.shared().writeValueAsString(task).getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();
	}
}
