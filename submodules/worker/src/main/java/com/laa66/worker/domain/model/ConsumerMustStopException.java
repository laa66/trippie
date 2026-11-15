package com.laa66.worker.domain.model;

/**
 * No task can be served until an operator fixes the worker's configuration: the task itself is not
 * at fault.
 *
 * <p>Deliberately not a {@link TextGenerationException}, a {@link PermanentTaskException} or a
 * {@link TransientTaskException}: it has no {@link FailedClass}, so nothing that records, retries or
 * dead-letters a POI failure can take it for one. Every task would fail the same way, so the worker
 * stops consuming and puts the task back in the queue instead. Neither the message nor the remedy
 * carries a credential or a URL.
 */
public class ConsumerMustStopException extends RuntimeException {

	private final String remedy;

	public ConsumerMustStopException(String message, String remedy) {
		super(message);
		this.remedy = remedy;
	}

	/** What the operator has to do, appended to the message where the stop is reported. */
	public String remedy() {
		return remedy;
	}
}
