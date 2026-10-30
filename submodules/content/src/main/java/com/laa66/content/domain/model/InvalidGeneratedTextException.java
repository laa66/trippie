package com.laa66.content.domain.model;

/**
 * The text handed to the write path cannot be stored. One type for both shapes, because every
 * consumer of it has the same decision to make: the generation is a permanent failure and retrying
 * it would spend money on the same bad output.
 *
 * <p>Both shapes end in a row that nothing would ever repair. An empty guide written as
 * {@code COMPLETED} is served as finished content and never retried; a truncated one is
 * indistinguishable from a finished one. So neither is accepted and neither is quietly repaired.
 */
public class InvalidGeneratedTextException extends RuntimeException {

	private InvalidGeneratedTextException(String message) {
		super(message);
	}

	public static InvalidGeneratedTextException tooLong(int length, int maxLength) {
		return new InvalidGeneratedTextException(
				"generated text is %d characters, limit is %d".formatted(length, maxLength));
	}

	public static InvalidGeneratedTextException blank() {
		return new InvalidGeneratedTextException("generated text is null or blank");
	}
}
