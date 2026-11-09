package com.laa66.worker.domain.model;

import java.util.Objects;

/** A finished guide text and the version of the prompt that produced it ({@code prompt_version}). */
public record GeneratedText(String text, String promptVersion) {

	public GeneratedText {
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(promptVersion, "promptVersion");
	}
}
