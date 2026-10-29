package com.laa66.content.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton-container base for every test that needs a real Postgres: one container per test JVM,
 * torn down by the Ryuk reaper on exit. Plain Postgres, not PostGIS: content has no geometry.
 *
 * <p>The image deliberately differs from the {@code postgis/postgis:18-3.6-alpine} that
 * infra/docker-compose.yml runs, because content needs none of PostGIS. The MAJOR must still track
 * compose: when compose moves to PG19, this line moves with it, or the ITs keep certifying content
 * against a server version production no longer runs. (spatial pins the compose image exactly --
 * see {@code AbstractPostgisIntegrationTest} -- because it does depend on the PostGIS build.)
 */
public abstract class AbstractPostgresIntegrationTest {

	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("content_db")
			.withUsername("trippie")
			.withPassword("trippie");

	static {
		POSTGRES.start();
	}

	@Autowired
	protected JdbcClient jdbc;

	/** Highest precedence: re-points the datasource and turns Flyway back on, which the test profile turns off. */
	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	/**
	 * Isolation lives HERE, not in one IT class: the container and the Spring context are shared
	 * across classes, so rows written by one class are visible to the next. Each new IT class would
	 * otherwise have to remember to re-write this cleanup, and the failure mode is order-dependent.
	 *
	 * <p>BOTH ends on purpose. {@code @AfterEach} alone makes "the table is empty when my test
	 * starts" a property of whoever ran before, which is a promise the next IT class cannot see it
	 * is relying on: several assertions here read the table globally ({@code count(*)},
	 * {@code .single()}), so a class that seeds in {@code @BeforeAll}, or does not extend this base
	 * at all, would break a test in a DIFFERENT class than the one at fault.
	 *
	 * <p>Not {@code @Transactional}: these tests assert rejections, and a rejected statement aborts
	 * the surrounding Postgres transaction, so nothing could follow one inside a shared transaction.
	 * Autocommit per statement plus these DELETEs is what keeps the rejection tests writable.
	 */
	@BeforeEach
	void clearPoiContentBefore() {
		jdbc.sql("DELETE FROM poi_content").update();
	}

	@AfterEach
	void clearPoiContentAfter() {
		jdbc.sql("DELETE FROM poi_content").update();
	}
}
