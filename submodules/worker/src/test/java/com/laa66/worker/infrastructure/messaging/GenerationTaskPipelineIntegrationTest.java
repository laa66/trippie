package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.support.ManualClock;
import com.laa66.worker.support.RabbitContainers;

/**
 * The delivery policy on a live broker, counted. The production {@code TaskConsumer} only logs, so
 * a scripted one stands in for it: what it does is chosen by the task's {@code osmId}, and every
 * call is recorded. Everything else is production wiring: the listener, the container factory, the
 * retry interceptor, the topology, the pacer bean.
 *
 * <p>The pacer runs on a {@link ManualClock}: its sleeps advance the clock instead of the test, so
 * the spacing is measured exactly. The retry backoff is the interceptor's own and runs in real time,
 * so the retry tests take ~20 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GenerationTaskPipelineIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final Instant TOKEN = Instant.parse("2026-10-31T12:00:00.123456Z");

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
	}

	@TestConfiguration
	static class Scripted {

		@Bean
		@Primary
		ManualClock manualClock() {
			return new ManualClock(0);
		}

		@Bean
		@Primary
		ProviderCallPacer.Sleeper manualSleeper(ManualClock clock) {
			return clock.sleeper();
		}

		@Bean
		@Primary
		ScriptedConsumer scriptedConsumer(ProviderCallPacer pacer, ManualClock clock) {
			return new ScriptedConsumer(pacer, clock);
		}
	}

	record Call(GenerateContentTask task, long nanoTime) {
	}

	static class ScriptedConsumer implements TaskConsumer {

		final List<Call> calls = new CopyOnWriteArrayList<>();

		final List<Long> providerStarts = new CopyOnWriteArrayList<>();

		final AtomicInteger inFlight = new AtomicInteger();

		final AtomicInteger maxInFlight = new AtomicInteger();

		private final ProviderCallPacer pacer;

		private final ManualClock clock;

		ScriptedConsumer(ProviderCallPacer pacer, ManualClock clock) {
			this.pacer = pacer;
			this.clock = clock;
		}

		@Override
		public void consume(GenerateContentTask task) {
			calls.add(new Call(task, System.nanoTime()));
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			try {
				String script = task.key().osmId().substring("node/".length()).split("-")[0];
				switch (script) {
					case "ok", "precheck" -> {
					}
					case "transient" -> throw new TransientTaskException("provider 503");
					case "permanent" -> throw new PermanentTaskException("provider 400");
					case "flaky" -> {
						if (attempts(task.key().osmId()) == 1) {
							throw new TransientTaskException("provider 503");
						}
					}
					case "paced" -> pacer.call(() -> providerStarts.add(clock.nanoTime()));
					case "slow" -> sleep(Duration.ofMillis(300));
					default -> throw new IllegalArgumentException(script);
				}
			}
			finally {
				inFlight.decrementAndGet();
			}
		}

		long attempts(String osmId) {
			return calls.stream().filter(c -> c.task().key().osmId().equals(osmId)).count();
		}

		void reset() {
			calls.clear();
			providerStarts.clear();
			maxInFlight.set(0);
		}

		private static void sleep(Duration duration) {
			try {
				Thread.sleep(duration);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Autowired
	private ScriptedConsumer consumer;

	@Autowired
	private ManualClock clock;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private RabbitAdmin admin;

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@BeforeEach
	void emptyQueuesAndRecords() {
		admin.purgeQueue(GenerationTopology.QUEUE);
		admin.purgeQueue(GenerationTopology.DEAD_LETTER_QUEUE);
		consumer.reset();
	}

	@Test
	void theConsumerIsOneThreadWithOneMessageInFlight() {
		SimpleMessageListenerContainer container =
				(SimpleMessageListenerContainer) listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID);

		assertThat(container.getActiveConsumerCount()).isEqualTo(1);

		publishWhileStopped("slow-1", "slow-2", "slow-3", "slow-4", "slow-5");

		await().atMost(Duration.ofSeconds(30)).until(() -> consumer.calls.size() == 5);
		assertThat(consumer.maxInFlight.get()).isEqualTo(1);
	}

	@Test
	void aTaskThatAlwaysFailsTransientlyIsAttemptedExactlyThreeTimesThenDeadLettered() throws Exception {
		Message sent = publish("transient-1");

		awaitDeadLetters(1, Duration.ofSeconds(60));
		// Long enough for a fourth attempt to show up, had the delivery gone back to the queue.
		Thread.sleep(3_000);

		assertThat(consumer.attempts("node/transient-1")).isEqualTo(3);
		assertThat(deadLetterCount()).isEqualTo(1);
		assertThat(workQueueCount()).isZero();

		List<Long> starts = consumer.calls.stream().map(Call::nanoTime).toList();
		assertThat(Duration.ofNanos(starts.get(1) - starts.get(0))).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
		assertThat(Duration.ofNanos(starts.get(2) - starts.get(1))).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(20));

		Message deadLetter = rabbit.receive(GenerationTopology.DEAD_LETTER_QUEUE, 5_000);
		assertThat(deadLetter.getBody()).isEqualTo(sent.getBody());
		@SuppressWarnings("unchecked")
		Map<String, Object> death = ((List<Map<String, Object>>) deadLetter.getMessageProperties().getHeaders().get("x-death")).get(0);
		assertThat(death).containsEntry("reason", "rejected").containsEntry("count", 1L);
	}

	@Test
	void aPermanentFailureIsAttemptedOnceThenDeadLettered() throws Exception {
		publish("permanent-1");

		awaitDeadLetters(1, Duration.ofSeconds(10));
		// A retry would come 5 s after the first attempt.
		Thread.sleep(7_000);

		assertThat(consumer.attempts("node/permanent-1")).isEqualTo(1);
		assertThat(deadLetterCount()).isEqualTo(1);
	}

	@Test
	void anUnclassifiedFailureIsAttemptedOnceToo() throws Exception {
		publish("unknown-1");

		awaitDeadLetters(1, Duration.ofSeconds(10));
		Thread.sleep(7_000);

		assertThat(consumer.attempts("node/unknown-1")).isEqualTo(1);
	}

	@Test
	void anUndecodablePayloadIsDeadLetteredWithoutReachingTheConsumer() throws Exception {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, json("{\"key\":{\"osmId\":\"node/1\",\"mode\":\"VIDEO\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}"));

		awaitDeadLetters(1, Duration.ofSeconds(10));

		assertThat(consumer.calls).isEmpty();
	}

	/**
	 * The retry interceptor wraps the listener call only. The container's own work before it runs
	 * outside, and a body carrying Spring's batch header is split there: a truncated one throws a
	 * plain {@code BufferUnderflowException} that no recoverer sees. The container's
	 * {@code defaultRequeueRejected} alone decides its fate; Boot's default (requeue) would hand it
	 * straight back, forever.
	 */
	@Test
	void aFailureOutsideTheRetryInterceptorIsDeadLetteredNotRequeued() throws Exception {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY,
				MessageBuilder.withBody(new byte[] { 0, 1 })
						.setHeader(MessageProperties.SPRING_BATCH_FORMAT, MessageProperties.BATCH_FORMAT_LENGTH_HEADER4)
						.build());

		awaitDeadLetters(1, Duration.ofSeconds(10));

		assertThat(consumer.calls).isEmpty();
		Message deadLetter = rabbit.receive(GenerationTopology.DEAD_LETTER_QUEUE, 5_000);
		@SuppressWarnings("unchecked")
		Map<String, Object> death = ((List<Map<String, Object>>) deadLetter.getMessageProperties().getHeaders().get("x-death")).get(0);
		assertThat(death).containsEntry("reason", "rejected").containsEntry("count", 1L);
	}

	@Test
	void aSuccessfulTaskIsAcknowledgedAndNothingIsDeadLettered() throws Exception {
		publish("ok-1");

		await().atMost(Duration.ofSeconds(10)).until(() -> consumer.attempts("node/ok-1") == 1);
		Thread.sleep(1_000);

		assertThat(workQueueCount()).isZero();
		assertThat(deadLetterCount()).isZero();
		assertThat(consumer.attempts("node/ok-1")).isEqualTo(1);
	}

	@Test
	void aTransientFailureThatClearsIsAcknowledgedOnTheSecondAttempt() throws Exception {
		publish("flaky-1");

		await().atMost(Duration.ofSeconds(20)).until(() -> consumer.attempts("node/flaky-1") == 2);
		Thread.sleep(1_000);

		assertThat(consumer.attempts("node/flaky-1")).isEqualTo(2);
		assertThat(deadLetterCount()).isZero();
		assertThat(workQueueCount()).isZero();
	}

	@Test
	void theLeaseTokenReachesTheConsumerBitForBit() {
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY,
				json(GenerationTaskDecodeTest.CONTENT_BODY.replace("node/42", "node/ok-token")));

		await().atMost(Duration.ofSeconds(10)).until(() -> consumer.attempts("node/ok-token") == 1);

		Instant delivered = consumer.calls.get(0).task().leasedAt();
		assertThat(delivered).isEqualTo(TOKEN);
		assertThat(delivered.getNano()).isEqualTo(123_456_000);
	}

	@Test
	void fiveTasksAtOnceStartTheirProviderCallsAtLeastFourSecondsApart() {
		publishWhileStopped("paced-1", "paced-2", "paced-3", "paced-4", "paced-5");

		await().atMost(Duration.ofSeconds(30)).until(() -> consumer.providerStarts.size() == 5);

		List<Long> starts = consumer.providerStarts;
		for (int i = 1; i < starts.size(); i++) {
			assertThat(Duration.ofNanos(starts.get(i) - starts.get(i - 1)))
					.as("gap before provider call %d", i + 1)
					.isGreaterThanOrEqualTo(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING);
		}
	}

	/** The pre-check path settles a task without the provider, so it must not be paced. */
	@Test
	void tasksThatNeverCallTheProviderAreNotPaced() {
		int sleepsBefore = clock.sleeps().size();
		long clockBefore = clock.nanoTime();

		publishWhileStopped("precheck-1", "precheck-2", "precheck-3", "precheck-4", "precheck-5");

		await().atMost(Duration.ofSeconds(10)).until(() -> consumer.calls.size() == 5);
		assertThat(clock.sleeps()).hasSize(sleepsBefore);
		assertThat(clock.nanoTime()).isEqualTo(clockBefore);
	}

	private void publishWhileStopped(String... scripts) {
		SimpleMessageListenerContainer container =
				(SimpleMessageListenerContainer) listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID);
		container.stop();
		try {
			for (String script : scripts) {
				publish(script);
			}
			await().atMost(Duration.ofSeconds(5)).until(() -> workQueueCount() == scripts.length);
		}
		finally {
			container.start();
		}
	}

	private Message publish(String script) {
		Message message = json("{\"key\":{\"osmId\":\"node/" + script
				+ "\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}");
		rabbit.send(GenerationTopology.EXCHANGE, GenerationTopology.ROUTING_KEY, message);
		return message;
	}

	private static Message json(String body) {
		return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();
	}

	private void awaitDeadLetters(int count, Duration within) {
		await().atMost(within).until(() -> deadLetterCount() >= count);
	}

	private long deadLetterCount() {
		return admin.getQueueInfo(GenerationTopology.DEAD_LETTER_QUEUE).getMessageCount();
	}

	private long workQueueCount() {
		return admin.getQueueInfo(GenerationTopology.QUEUE).getMessageCount();
	}
}
