package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.PublishBudget;
import com.laa66.content.support.RabbitContainers;

import tools.jackson.databind.json.JsonMapper;

/**
 * The publisher against a live broker: where a message lands, what it turns back into, and what the
 * lease token is still worth at the far end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RabbitEventPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");

	private static final long RECEIVE_TIMEOUT_MS = 5_000;

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
	}

	@Autowired
	private EventPublisher publisher;

	@Autowired
	private ContentRepository repository;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private AmqpAdmin admin;

	@Autowired
	private CachingConnectionFactory connectionFactory;

	@BeforeEach
	void emptyTheQueues() {
		admin.purgeQueue(GenerationTopology.QUEUE);
		admin.purgeQueue(GenerationTopology.DEAD_LETTER_QUEUE);
	}

	@Test
	void aTaskLandsInTheWorkQueueOnTheRoutingKeyAndReadsBackAsTheSameType() {
		GenerateContentTask task = new GenerateContentTask(KEY, Instant.parse("2026-10-31T12:00:00.123456Z"));

		publisher.publish(task);

		Message received = receive(GenerationTopology.QUEUE);
		assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("content.generate");
		assertThat(received.getMessageProperties().getReceivedExchange()).isEqualTo("trippie.content");
		assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(decode(received)).isEqualTo(task);
	}

	@Test
	void theTokenThatCrossedTheBrokerStillPassesTheFence() {
		GenerationLease lease = repository.leaseGeneration(KEY);
		assertThat(lease.outcome()).isEqualTo(LeaseOutcome.GRANTED);

		publisher.publish(new GenerateContentTask(KEY, lease.leasedAt()));
		GenerateContentTask delivered = decode(receive(GenerationTopology.QUEUE));

		repository.failGeneration(delivered.key(), delivered.leasedAt(), FailedClass.TRANSIENT, "provider 503");

		assertThat(repository.find(KEY).orElseThrow().status()).isEqualTo(ContentStatus.FAILED);
	}

	/**
	 * The counter-proof to the test above, with sub-millisecond digits forced onto the row because a
	 * real token ends in three zeros once in a thousand runs.
	 */
	@Test
	void aTokenRoundedToMillisecondsMissesTheFenceSilently() {
		repository.leaseGeneration(KEY);
		jdbc.sql("UPDATE poi_content SET started_at = '2026-10-31 12:00:00.123456+00'").update();
		Instant rounded = Instant.parse("2026-10-31T12:00:00.123Z");

		publisher.publish(new GenerateContentTask(KEY, rounded));
		GenerateContentTask delivered = decode(receive(GenerationTopology.QUEUE));

		repository.failGeneration(delivered.key(), delivered.leasedAt(), FailedClass.TRANSIENT, "provider 503");

		assertThat(repository.find(KEY).orElseThrow().status()).isEqualTo(ContentStatus.PROCESSING);
	}

	@Test
	void aRejectedTaskEndsUpInTheDeadLetterQueue() {
		GenerateContentTask task = new GenerateContentTask(KEY, Instant.parse("2026-10-31T12:00:00.123456Z"));
		publisher.publish(task);

		rabbit.execute(channel -> {
			long deliveryTag = channel.basicGet(GenerationTopology.QUEUE, false).getEnvelope().getDeliveryTag();
			channel.basicNack(deliveryTag, false, false);
			return null;
		});

		Message deadLettered = receive(GenerationTopology.DEAD_LETTER_QUEUE);
		assertThat(decode(deadLettered)).isEqualTo(task);
		assertThat(deadLettered.getMessageProperties().getHeaders()).containsKey("x-death");
	}

	/**
	 * Broker reachable, connection fine, no queue behind the routing key: the case a confirm alone
	 * reports as an ack. Repeated because the return and the ack are two frames, and reading
	 * {@code getReturned()} before the first one was processed would pass now and then.
	 */
	@Test
	void anUnroutableMessageIsAnExceptionEveryTime() {
		Binding binding = workBinding();
		admin.removeBinding(binding);
		try {
			for (int attempt = 0; attempt < 100; attempt++) {
				assertThatThrownBy(() -> publisher.publish(new GenerateContentTask(KEY, Instant.now())))
						.isInstanceOf(EventPublicationException.class)
						.hasMessageContaining("NO_ROUTE");
			}
		}
		finally {
			admin.declareBinding(binding);
		}
	}

	@Test
	void aMissingExchangeIsAnException() {
		admin.deleteExchange(GenerationTopology.EXCHANGE);
		try {
			assertThatThrownBy(() -> publisher.publish(new GenerateContentTask(KEY, Instant.now())))
					.isInstanceOf(EventPublicationException.class);
		}
		finally {
			admin.declareExchange(new DirectExchange(GenerationTopology.EXCHANGE));
			admin.declareBinding(workBinding());
		}
	}

	/** {@code make down-hard} under a running content: nothing to bind to until a reconnect. */
	@Test
	void aBrokerThatLostItsTopologyGetsItBackOnReconnectBeforeThePublish() {
		admin.deleteQueue(GenerationTopology.QUEUE);
		admin.deleteQueue(GenerationTopology.DEAD_LETTER_QUEUE);
		admin.deleteExchange(GenerationTopology.EXCHANGE);
		admin.deleteExchange(GenerationTopology.DEAD_LETTER_EXCHANGE);
		connectionFactory.resetConnection();

		GenerateContentTask task = new GenerateContentTask(KEY, Instant.parse("2026-10-31T12:00:00.123456Z"));
		publisher.publish(task);

		assertThat(decode(receive(GenerationTopology.QUEUE))).isEqualTo(task);
	}

	/**
	 * Accepted connection, nothing ever confirmed: a memory alarm blocks publishers without closing
	 * anything. It also pins why {@link EventPublicationException} says "not known to be queued":
	 * the bytes sit in the socket and arrive when the alarm clears.
	 */
	@Test
	void aBrokerThatStopsConfirmingIsAnExceptionAndTheTaskMayStillArrive() throws Exception {
		GenerateContentTask task = new GenerateContentTask(KEY, Instant.parse("2026-10-31T12:00:00.123456Z"));
		watermark("0");
		try {
			assertThat(PublishBudget.assertFailsWithinBudget(publisher, task)).hasMessageContaining("no broker confirm");
		}
		finally {
			watermark("0.4");
		}

		assertThat(decode(receive(GenerationTopology.QUEUE))).isEqualTo(task);
	}

	/**
	 * The first publish leaves its channel waiting for a confirm, so every later one has to open a
	 * new channel, and that {@code channel.open} is what hangs for 10 min without
	 * {@code channel-rpc-timeout} (measured: 600 s; with it, 2 s). Each timed-out open leaves a
	 * channel behind, hence the run of publishes after the alarm clears.
	 */
	@Test
	void everyPublishUnderAnAlarmFailsInsideTheBudgetAndPublishingRecovers() throws Exception {
		GenerateContentTask task = new GenerateContentTask(KEY, Instant.parse("2026-10-31T12:00:00.123456Z"));
		watermark("0");
		try {
			for (int attempt = 0; attempt < 3; attempt++) {
				PublishBudget.assertFailsWithinBudget(publisher, task);
			}
		}
		finally {
			watermark("0.4");
		}

		for (int attempt = 0; attempt < 20; attempt++) {
			publisher.publish(task);
		}
	}

	private static void watermark(String fraction) throws Exception {
		assertThat(BROKER.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", fraction).getExitCode()).isZero();
	}

	private static Binding workBinding() {
		return BindingBuilder.bind(new Queue(GenerationTopology.QUEUE))
				.to(new DirectExchange(GenerationTopology.EXCHANGE))
				.with(GenerationTopology.ROUTING_KEY);
	}

	private Message receive(String queue) {
		Message message = rabbit.receive(queue, RECEIVE_TIMEOUT_MS);
		assertThat(message).as("nothing arrived in " + queue).isNotNull();
		return message;
	}

	private static GenerateContentTask decode(Message message) {
		String body = new String(message.getBody(), StandardCharsets.UTF_8);
		return JsonMapper.shared().readValue(body, GenerateContentTask.class);
	}
}
