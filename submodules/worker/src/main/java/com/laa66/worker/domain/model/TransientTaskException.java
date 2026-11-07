package com.laa66.worker.domain.model;

/**
 * A failure that may go away on its own: provider 5xx, a passing 429, a timeout, an unreachable
 * spatial. The only failure the delivery is retried for; every other exception is final on the
 * first attempt.
 */
public class TransientTaskException extends RuntimeException {

	public TransientTaskException(String message) {
		super(message);
	}

	public TransientTaskException(String message, Throwable cause) {
		super(message, cause);
	}
}
