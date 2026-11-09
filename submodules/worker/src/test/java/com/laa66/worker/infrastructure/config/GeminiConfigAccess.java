package com.laa66.worker.infrastructure.config;

import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.domain.service.ProviderCallPacer;

/** The shipped {@code textGenerator} bean method, for tests outside this package. */
public final class GeminiConfigAccess {

	public static final String MODEL = "gemini-3.5-flash-lite";

	public static final int MAX_OUTPUT_TOKENS = 1024;

	private GeminiConfigAccess() {
	}

	public static TextGenerator textGenerator(ApiKeyFile keyFile, ProviderCallPacer pacer, String baseUrl) {
		return new GeminiConfig().textGenerator(keyFile, pacer, baseUrl, MODEL, MAX_OUTPUT_TOKENS);
	}
}
