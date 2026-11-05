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
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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
 *
 * <p><strong>A missing exchange resets the connection.</strong> The topology is declared when a
 * connection opens, and a declaration that fails on an open connection (a broker that lost its
 * definitions and refuses or misses the redeclaration on reconnect) leaves that connection cached
 * with nothing to publish to: every publish then fails with 404 until the connection happens to
 * drop. The publish that meets the 404 still fails, and the reset makes the next one open a new
 * connection, which declares again. The 404 is only visible as the nack reason, which is the text of
 * the broker's channel close, so it is matched as text.
 */
public class RabbitEventPublisher implements EventPublisher {

	private static final String MISSING_EXCHANGE = "NOT_FOUND - no exchange '" + GenerationTopology.EXCHANGE + "'";

	private final RabbitOperations rabbit;

	private final ConnectionFactory connectionFactory;

	private final Duration confirmTimeout;

	public RabbitEventPublisher(RabbitOperations rabbit, ConnectionFactory connectionFactory, Duration confirmTimeout) {
		this.rabbit = rabbit;
		this.connectionFactory = connectionFactory;
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
			if (confirm.reason() != null && confirm.reason().contains(MISSING_EXCHANGE)) {
				connectionFactory.resetConnection();
			}
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
