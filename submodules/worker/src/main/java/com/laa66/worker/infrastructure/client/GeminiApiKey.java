package com.laa66.worker.infrastructure.client;

import java.util.Objects;

/**
 * The provider credential. Its {@link #toString()} is redacted so that no log line, health detail or
 * exception message that prints the object can print the value.
 */
public final class GeminiApiKey {

	private final String value;

	public GeminiApiKey(String value) {
		Objects.requireNonNull(value, "value");
		if (value.isEmpty()) {
			throw new IllegalArgumentException("empty API key");
		}
		this.value = value;
	}

	String value() {
		return value;
	}

	/** Replaces every occurrence of the key in {@code text}, for details built from provider bodies. */
	String redact(String text) {
		return text.replace(value, "[redacted]");
	}

	@Override
	public String toString() {
		return "GeminiApiKey[redacted]";
	}
}
