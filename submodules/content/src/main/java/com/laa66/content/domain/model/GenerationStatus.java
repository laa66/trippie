package com.laa66.content.domain.model;

/**
 * The answer to a generation request: a status, and the class of the last failure exactly when the
 * status is {@link ContentStatus#FAILED}, as on a read of the row. Never content, which has one
 * place to be read from.
 */
public record GenerationStatus(ContentStatus status, FailedClass failedClass) {

	public static GenerationStatus processing() {
		return new GenerationStatus(ContentStatus.PROCESSING, null);
	}

	public static GenerationStatus completed() {
		return new GenerationStatus(ContentStatus.COMPLETED, null);
	}

	public static GenerationStatus failed(FailedClass failedClass) {
		return new GenerationStatus(ContentStatus.FAILED, failedClass);
	}
}
