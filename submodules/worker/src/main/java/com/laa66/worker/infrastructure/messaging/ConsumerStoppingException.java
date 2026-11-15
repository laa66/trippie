package com.laa66.worker.infrastructure.messaging;

/**
 * An attempt that did not start because the listener container is stopping. Not a failure of the
 * task: the delivery goes back to the queue, and nothing is spent on it here.
 */
final class ConsumerStoppingException extends RuntimeException {

	ConsumerStoppingException() {
		super("the consumer is stopping, the attempt was not started");
	}
}
