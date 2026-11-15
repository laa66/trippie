package com.laa66.worker.domain.port.in;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * Inbound port: executes one delivered generation task.
 *
 * <p>A normal return acknowledges the message. A {@link TransientTaskException} gets the delivery
 * retried in-process, up to the attempt budget of the adapter; any other exception ends it after
 * this one attempt. An exhausted or final delivery is handed to {@link #giveUp} and then goes to the
 * dead-letter queue, never back to the work queue.
 */
public interface TaskConsumer {

	void consume(GenerateContentTask task);

	/**
	 * The delivery ended without a result: records {@code failure}, the last attempt's exception, as
	 * the task's failure. Not called for a delivery abandoned because the consumer is stopping or
	 * must stop (a {@link ConsumerMustStopException}): those tasks go back to the queue and are not
	 * failures of their POI. Throws only a {@link ConsumerMustStopException} met while recording,
	 * which stops the consumer and requeues the task the same way; any other failure to record is
	 * logged.
	 */
	void giveUp(GenerateContentTask task, Throwable failure);
}
