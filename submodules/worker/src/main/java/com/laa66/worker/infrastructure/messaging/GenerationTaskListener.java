package com.laa66.worker.infrastructure.messaging;

import java.util.function.BooleanSupplier;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.port.in.TaskConsumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The listening adapter: decodes the body and hands the task to {@link TaskConsumer}. The retry
 * policy is not here but on the container ({@link TaskRetryPolicy}), so each retry runs this method,
 * the decode included, again.
 *
 * <p>The body is decoded here and not by a message converter, so a malformed payload surfaces as a
 * {@link PermanentTaskException} inside the retry interceptor: one attempt, then the DLQ. The mapper
 * is {@link JsonMapper#shared()} for the reason content's publisher uses it: {@code spring.jackson.*}
 * must not reshape a contract another service writes. The lease token is parsed from its ISO-8601
 * text into an {@code Instant}, which keeps every digit content sent.
 *
 * <p>No attempt starts once the container is stopping. Without this check an attempt whose backoff
 * ends inside the container's shutdown wait runs in full, paid provider call included, while the
 * application closes (measured in the M4-09 review); with it the attempt ends before the pre-check
 * and the delivery goes back to the queue ({@link TaskRetryPolicy}).
 */
public class GenerationTaskListener {

	public static final String LISTENER_ID = "generation-task-listener";

	private final TaskConsumer consumer;

	private final BooleanSupplier stopping;

	public GenerationTaskListener(TaskConsumer consumer, BooleanSupplier stopping) {
		this.consumer = consumer;
		this.stopping = stopping;
	}

	@RabbitListener(id = LISTENER_ID, queues = GenerationTopology.QUEUE)
	public void onMessage(Message message) {
		if (stopping.getAsBoolean()) {
			throw new ConsumerStoppingException();
		}
		consumer.consume(decode(message.getBody()));
	}

	static GenerateContentTask decode(byte[] body) {
		try {
			return JsonMapper.shared().readValue(body, GenerateContentTask.class);
		}
		catch (JacksonException ex) {
			throw new PermanentTaskException("undecodable generation task", ex);
		}
	}
}
