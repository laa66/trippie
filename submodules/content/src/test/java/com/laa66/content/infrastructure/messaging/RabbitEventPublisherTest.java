package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.PoiContentKey;

import tools.jackson.databind.json.JsonMapper;

/**
 * Every verdict the broker can give, plus the wire format. What a real broker answers in each
 * situation is pinned by the integration tests next to this class.
 */
class RabbitEventPublisherTest {

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	private final RabbitOperations rabbit = mock(RabbitOperations.class);

	private final RabbitEventPublisher publisher = new RabbitEventPublisher(rabbit, Duration.ofMillis(50));

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void theWireFormatIsPlainJsonWithTheTokenAsFullPrecisionText() {
		answerWith(new CorrelationData.Confirm(true, null), null);

		publisher.publish(TASK);

		ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
		verify(rabbit).send(eq("trippie.content"), eq("content.generate"), sent.capture(), any(CorrelationData.class));
		assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(
				"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},"
						+ "\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}");
		MessageProperties properties = sent.getValue().getMessageProperties();
		assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(properties.getHeaders()).doesNotContainKey("__TypeId__");
	}

	@Test
	void nanosecondsSurviveTheRoundTripToo() {
		GenerateContentTask task = new GenerateContentTask(TASK.key(), Instant.parse("2026-10-31T12:00:00.123456789Z"));

		String wire = JsonMapper.shared().writeValueAsString(task);

		assertThat(JsonMapper.shared().readValue(wire, GenerateContentTask.class)).isEqualTo(task);
	}

	@Test
	void aMessageWithoutATokenCannotBeBuilt() {
		assertThatThrownBy(() -> new GenerateContentTask(TASK.key(), null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void aMessageWithAnIncompleteKeyCannotBeBuilt() {
		Instant token = TASK.leasedAt();

		assertThatThrownBy(() -> new GenerateContentTask(new PoiContentKey(null, ContentMode.TEXT, "pl"), token))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GenerateContentTask(new PoiContentKey("node/42", null, "pl"), token))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GenerateContentTask(new PoiContentKey("node/42", ContentMode.TEXT, null), token))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void anAckedAndRoutedMessageIsPublished() {
		answerWith(new CorrelationData.Confirm(true, null), null);

		assertThatCode(() -> publisher.publish(TASK)).doesNotThrowAnyException();
	}

	@Test
	void aNackIsAnException() {
		answerWith(new CorrelationData.Confirm(false, "queue overflow"), null);

		assertThatThrownBy(() -> publisher.publish(TASK))
				.isInstanceOf(EventPublicationException.class)
				.hasMessageContaining("queue overflow");
	}

	@Test
	void anAckedButReturnedMessageIsAnException() {
		ReturnedMessage returned = new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "x", "y");
		answerWith(new CorrelationData.Confirm(true, null), returned);

		assertThatThrownBy(() -> publisher.publish(TASK))
				.isInstanceOf(EventPublicationException.class)
				.hasMessageContaining("NO_ROUTE");
	}

	@Test
	void aConfirmThatNeverArrivesIsAnException() {
		assertThatThrownBy(() -> publisher.publish(TASK))
				.isInstanceOf(EventPublicationException.class)
				.hasMessageContaining("no broker confirm");
	}

	@Test
	void aSendFailureIsAnException() {
		doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
				.when(rabbit).send(any(), any(), any(Message.class), any(CorrelationData.class));

		assertThatThrownBy(() -> publisher.publish(TASK))
				.isInstanceOf(EventPublicationException.class)
				.hasCauseInstanceOf(AmqpConnectException.class);
	}

	@Test
	void anInterruptWhileWaitingKeepsTheFlag() {
		Thread.currentThread().interrupt();

		assertThatThrownBy(() -> publisher.publish(TASK)).isInstanceOf(EventPublicationException.class);

		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	private void answerWith(CorrelationData.Confirm confirm, ReturnedMessage returned) {
		doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			correlation.setReturned(returned);
			correlation.getFuture().complete(confirm);
			return null;
		}).when(rabbit).send(any(), any(), any(Message.class), any(CorrelationData.class));
	}
}
