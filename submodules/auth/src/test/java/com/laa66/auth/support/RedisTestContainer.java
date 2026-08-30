package com.laa66.auth.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis container, mirroring {@link AbstractPostgresIntegrationTest}: one instance per
 * test JVM, started on first class load and reaped when the JVM exits. Kept as a standalone holder
 * (not a base class) so tests that only need Postgres — e.g. the migration IT — do not start Redis.
 *
 * <p>Pinned to the exact image infra/docker-compose.yml runs, so the store behaves like production.
 */
public final class RedisTestContainer {

	private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");

	public static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	private RedisTestContainer() {
	}
}
