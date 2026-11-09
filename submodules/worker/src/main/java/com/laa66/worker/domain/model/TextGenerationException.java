package com.laa66.worker.domain.model;

import java.util.Objects;

/**
 * The typed failure of {@code TextGenerator}. The message is the operator-facing detail and never
 * carries the provider credential.
 */
public class TextGenerationException extends RuntimeException {

	private final FailedClass failedClass;

	public TextGenerationException(FailedClass failedClass, String message) {
		this(failedClass, message, null);
	}

	public TextGenerationException(FailedClass failedClass, String message, Throwable cause) {
		super(message, cause);
		this.failedClass = Objects.requireNonNull(failedClass, "failedClass");
	}

	public FailedClass failedClass() {
		return failedClass;
	}
}
