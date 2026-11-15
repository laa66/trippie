package com.laa66.worker.infrastructure.config;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The {@code consumer} component of {@code /health}: DOWN once the listener was stopped because no
 * task could be served (a refused key, content or spatial misconfigured). Nothing clears it but a
 * restart, which is also the only way a fixed configuration is read.
 */
public final class ConsumerHealth implements HealthIndicator {

	private final AtomicReference<String> stopped = new AtomicReference<>();

	/** The first reason is kept: it is the one that stopped the consumer. */
	void stopped(String reason) {
		stopped.compareAndSet(null, reason);
	}

	@Override
	public Health health() {
		String reason = stopped.get();
		return reason == null ? Health.up().build() : Health.down().withDetail("reason", reason).build();
	}
}
