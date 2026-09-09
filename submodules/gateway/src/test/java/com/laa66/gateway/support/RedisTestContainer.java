package com.laa66.gateway.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis container shared by the gateway ITs that need a live denylist store — one instance
 * per test JVM, started on first class load and reaped at JVM exit. Pinned to the exact image
 * infra/docker-compose.yml runs so the store behaves like production.
 */
public final class RedisTestContainer {

	public static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	static {
		REDIS.start();
	}

	private RedisTestContainer() {
	}
}
