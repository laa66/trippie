package com.laa66.worker.infrastructure.config;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The {@code gemini} component of {@code /health}: DOWN when the key file gave no key, or when the
 * provider has refused the key since startup. Nothing clears a refusal but a restart, which is also
 * the only way a fixed key file is read.
 */
public final class GeminiKeyHealth implements HealthIndicator {

	private final ApiKeyFile keyFile;

	private final AtomicReference<String> rejection = new AtomicReference<>();

	GeminiKeyHealth(ApiKeyFile keyFile) {
		this.keyFile = keyFile;
	}

	/** The first reason is kept: it is the one that stopped the consumer. */
	void keyRejected(String reason) {
		rejection.compareAndSet(null, reason);
	}

	@Override
	public Health health() {
		if (keyFile instanceof ApiKeyFile.Unavailable(String reason)) {
			return Health.down().withDetail("reason", reason).build();
		}
		String rejected = rejection.get();
		return rejected == null ? Health.up().build() : Health.down().withDetail("reason", rejected).build();
	}
}
