package com.laa66.spatial.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container base for every test that needs a real PostGIS database: one
 * container per test JVM, started on first class load and torn down by the Ryuk
 * reaper when the JVM exits. Deliberately not {@code withReuse(true)} — that needs a
 * per-machine {@code ~/.testcontainers.properties} opt-in and leaves containers running.
 *
 * <p>Only tests that genuinely need a database extend this. Plain Spring context tests must
 * not, so the rest of the suite stays runnable without Docker. M1-05 adds the Spring
 * datasource wiring here once there is a read path to cover it.
 */
public abstract class AbstractPostgisIntegrationTest {

	/** Pinned to the image infra/docker-compose.yml runs, so tests and dev hit the same PostGIS. */
	private static final DockerImageName POSTGIS_IMAGE = DockerImageName
			.parse("postgis/postgis:18-3.6-alpine")
			.asCompatibleSubstituteFor("postgres");

	protected static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(POSTGIS_IMAGE)
			.withDatabaseName("spatial_db")
			.withUsername("trippie")
			.withPassword("trippie");

	static {
		POSTGIS.start();
	}
}
