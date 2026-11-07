package com.laa66.worker.domain.port.in;

import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * Inbound port: executes one delivered generation task.
 *
 * <p>A normal return acknowledges the message. A {@link TransientTaskException} gets the delivery
 * retried in-process, up to the attempt budget of the adapter; any other exception ends it after
 * this one attempt. Either way an exhausted delivery goes to the dead-letter queue, never back to
 * the work queue.
 */
public interface TaskConsumer {

	void consume(GenerateContentTask task);
}
