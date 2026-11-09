package com.laa66.worker.domain.model;

/**
 * A failure that repeating cannot change: a payload that does not decode, an unknown mode, a
 * provider answer classified PERMANENT. Any exception that is not a {@link TransientTaskException} is treated the same
 * way, so this type exists to name the case in logs, not to opt out of retries.
 */
public class PermanentTaskException extends RuntimeException {

	public PermanentTaskException(String message) {
		super(message);
	}

	public PermanentTaskException(String message, Throwable cause) {
		super(message, cause);
	}
}
