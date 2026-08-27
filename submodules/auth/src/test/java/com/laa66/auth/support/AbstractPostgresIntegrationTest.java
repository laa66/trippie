package com.laa66.auth.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container base for every auth test that needs a real Postgres: one container per
 * test JVM, started on first class load and torn down by the Ryuk reaper when the JVM exits.
 * Deliberately not {@code withReuse(true)} — that needs a per-machine
 * {@code ~/.testcontainers.properties} opt-in and leaves containers running.
 *
 * <p>Pinned to the exact image infra/docker-compose.yml runs (the shared cluster), so auth tests
 * hit the same server binary that hosts auth_db in every environment. auth_db uses no spatial
 * types, so the PostGIS extension is simply never enabled on it — mirroring infra/init, where
 * PostGIS is created only on spatial_db.
 */
public abstract class AbstractPostgresIntegrationTest {

	private static final DockerImageName POSTGRES_IMAGE = DockerImageName
			.parse("postgis/postgis:18-3.6-alpine")
			.asCompatibleSubstituteFor("postgres");

	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
			.withDatabaseName("auth_db")
			.withUsername("trippie")
			.withPassword("trippie");

	static {
		POSTGRES.start();
	}
}
