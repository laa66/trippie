package com.laa66.worker.infrastructure.messaging;

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
 */
public class GenerationTaskListener {

	public static final String LISTENER_ID = "generation-task-listener";

	private final TaskConsumer consumer;

	public GenerationTaskListener(TaskConsumer consumer) {
		this.consumer = consumer;
	}

	@RabbitListener(id = LISTENER_ID, queues = GenerationTopology.QUEUE)
	public void onMessage(Message message) {
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
