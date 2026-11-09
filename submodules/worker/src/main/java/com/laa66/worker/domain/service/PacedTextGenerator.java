package com.laa66.worker.domain.service;

import java.util.Objects;

import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.port.out.TextGenerator;

/**
 * The provider adapter behind the pacer. The pacing is part of the {@link TextGenerator} the handler
 * gets, so there is no way to call the provider around it (review M4-09).
 */
public class PacedTextGenerator implements TextGenerator {

	private final ProviderCallPacer pacer;

	private final TextGenerator provider;

	public PacedTextGenerator(ProviderCallPacer pacer, TextGenerator provider) {
		this.pacer = Objects.requireNonNull(pacer, "pacer");
		this.provider = Objects.requireNonNull(provider, "provider");
	}

	@Override
	public GeneratedText generate(PoiPrompt prompt) {
		return pacer.call(() -> provider.generate(prompt));
	}
}
