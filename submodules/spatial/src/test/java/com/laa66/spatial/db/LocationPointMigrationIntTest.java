package com.laa66.spatial.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.laa66.spatial.support.AbstractPostgisIntegrationTest;

/**
 * Applies the real V1 migration to a database created clean for this test, so the
 * assertions describe what a virgin spatial_db looks like after Flyway runs.
 *
 * <p>Assertions are written to fail on a weakened schema, not merely to pass on a correct
 * one: catalog introspection pins what a functional test cannot see — a ninth category slug,
 * a dropped NOT NULL, a dropped default.
 */
class LocationPointMigrationIntTest extends AbstractPostgisIntegrationTest {

	private static final String DATABASE = "migration_check";

	private static final String GEOM = "ST_SetSRID(ST_MakePoint(17.03, 51.11), 4326)::geography";

	private static final List<String> CATEGORY_SLUGS = List.of(
			"public_art", "monuments", "heritage", "sacred",
			"museums", "viewpoints", "architecture", "attractions");

	private static MigrateResult migrateResult;
	private static Connection connection;

	@BeforeAll
	static void migrateCleanDatabase() throws SQLException {
		try (Connection admin = DriverManager.getConnection(
				POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
				Statement statement = admin.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
			statement.execute("CREATE DATABASE " + DATABASE);
		}

		String url = jdbcUrlFor(DATABASE);
		// Mirrors infra/init/01-init-databases.sql: the extension is cluster-level and
		// superuser-only, so the migration may assume it and must not create it.
		try (Connection fresh = DriverManager.getConnection(url, POSTGIS.getUsername(), POSTGIS.getPassword());
				Statement statement = fresh.createStatement()) {
			statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
		}

		migrateResult = Flyway.configure()
				.dataSource(url, POSTGIS.getUsername(), POSTGIS.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();

		connection = DriverManager.getConnection(url, POSTGIS.getUsername(), POSTGIS.getPassword());
		connection.setAutoCommit(false);
	}

	/** Keeps the container's own URL parameters (loggerLevel=OFF), swapping only the database. */
	private static String jdbcUrlFor(String database) {
		String url = POSTGIS.getJdbcUrl();
		int lastSlash = url.lastIndexOf('/');
		int query = url.indexOf('?', lastSlash);
		String parameters = (query < 0) ? "" : url.substring(query);
		return url.substring(0, lastSlash + 1) + database + parameters;
	}

	@AfterAll
	static void closeConnection() throws SQLException {
		if (connection != null) {
			connection.close();
		}
	}

	@BeforeEach
	@AfterEach
	void discardWrites() throws SQLException {
		connection.rollback();
	}

	@Test
	void v1AppliesOnACleanDatabase() {
		assertThat(migrateResult.success).isTrue();
		assertThat(migrateResult.migrations)
				.singleElement()
				.satisfies(migration -> {
					assertThat(migration.version).isEqualTo("1");
					assertThat(migration.description).isEqualTo("location point");
				});
	}

	@Test
	void tableCarriesOnlyTheDedicatedColumns() throws SQLException {
		assertThat(query("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point'
				""")).containsExactlyInAnyOrder(
						"osm_id", "name", "description", "category", "geom", "tags", "updated_at");
	}

	@Test
	void osmIdIsThePrimaryKeyAndNeverNull() throws SQLException {
		assertThat(query("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point' AND column_name = 'osm_id'
				""")).containsExactly("NO");

		assertThat(primaryKeyColumns()).containsExactly("osm_id");

		// A nullable key would do more than allow NULLs: UNIQUE permits many of them, which
		// turns M1-03's ON CONFLICT (osm_id) upsert into unbounded row growth.
		assertRejected(() -> insert(null, "monuments"));
		assertRejected(() -> upsert(null, "monuments"));
	}

	@Test
	void osmIdRejectsDuplicates() throws SQLException {
		insert("node/1", "monuments");

		assertRejected(() -> insert("node/1", "museums"));
	}

	@Test
	void repeatedUpsertsOnOsmIdLeaveASingleRow() throws SQLException {
		upsert("node/42", "monuments");
		upsert("node/42", "museums");
		upsert("node/42", "heritage");

		assertThat(query("SELECT count(*)::text FROM location_point WHERE osm_id = 'node/42'"))
				.containsExactly("1");
		assertThat(query("SELECT category FROM location_point WHERE osm_id = 'node/42'"))
				.containsExactly("heritage");
	}

	@Test
	void geomIsANotNullGeographyPointIn4326() throws SQLException {
		assertThat(query("""
				SELECT udt_name || ':' || is_nullable
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point' AND column_name = 'geom'
				""")).containsExactly("geography:NO");

		assertThat(query("""
				SELECT type || ':' || srid
				FROM geography_columns
				WHERE f_table_schema = 'public' AND f_table_name = 'location_point'
				  AND f_geography_column = 'geom'
				""")).containsExactly("Point:4326");
	}

	/**
	 * Pins the index set exactly, not just the presence of the two required ones: the frozen
	 * decision is that {@code tags} gets NO GIN index because nothing queries tags in M1, and
	 * an anyMatch-style assertion cannot see an extra index being added.
	 */
	@Test
	void tableHasExactlyTheGistCategoryAndPrimaryKeyIndexes() throws SQLException {
		assertThat(query("""
				SELECT indexname FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'location_point'
				""")).containsExactlyInAnyOrder(
						"location_point_pkey", "idx_location_point_geom", "idx_location_point_category");

		List<String> definitions = query("""
				SELECT indexdef FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'location_point'
				""");

		assertThat(definitions).anyMatch(definition -> definition.contains("USING gist (geom)"));
		assertThat(definitions).anyMatch(definition -> definition.contains("USING btree (category)"));
	}

	/**
	 * The CHECK is the only thing catching category drift between the Go loader, this
	 * service and the TS client — there is no shared codegen artifact — so it is pinned to
	 * exactly eight slugs. Inserting the known eight cannot detect a ninth being added.
	 */
	@Test
	void categoryCheckPinsExactlyTheEightSlugs() throws SQLException {
		assertThat(query("""
				SELECT unnest(regexp_matches(pg_get_constraintdef(c.oid), '''([a-z_]+)''::text', 'g'))
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'public' AND t.relname = 'location_point'
				  AND c.conname = 'location_point_category_check'
				""")).containsExactlyInAnyOrderElementsOf(CATEGORY_SLUGS);

		for (int i = 0; i < CATEGORY_SLUGS.size(); i++) {
			String slug = CATEGORY_SLUGS.get(i);
			int index = i;
			assertThatCode(() -> insert("node/" + index, slug))
					.as("category %s must be accepted", slug)
					.doesNotThrowAnyException();
		}

		assertRejected(() -> insert("node/rejected", "restaurants"));
	}

	/** Separate from the CHECK: `category IN (...)` yields NULL on NULL, so NOT NULL is what rejects it. */
	@Test
	void categoryIsNotNull() throws SQLException {
		assertThat(query("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point' AND column_name = 'category'
				""")).containsExactly("NO");

		assertRejected(() -> insert("node/no-category", null));
	}

	@Test
	void tagsIsNotNullAndDefaultsToAnEmptyObject() throws SQLException {
		assertThat(query("""
				SELECT data_type || ':' || is_nullable || ':' || coalesce(column_default, '<none>')
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point' AND column_name = 'tags'
				""")).containsExactly("jsonb:NO:'{}'::jsonb");

		insert("node/2", "heritage");
		assertThat(query("SELECT tags::text FROM location_point WHERE osm_id = 'node/2'"))
				.containsExactly("{}");

		assertRejected(() -> execute(
				"INSERT INTO location_point (osm_id, category, geom, tags) VALUES ('node/3', 'heritage', "
						+ GEOM + ", NULL)"));
	}

	@Test
	void updatedAtIsNotNullAndDefaultsToNow() throws SQLException {
		assertThat(query("""
				SELECT data_type || ':' || is_nullable || ':' || coalesce(column_default, '<none>')
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'location_point' AND column_name = 'updated_at'
				""")).containsExactly("timestamp with time zone:NO:now()");

		insert("node/4", "heritage");
		assertThat(query("""
				SELECT (updated_at > now() - interval '1 minute')::text
				FROM location_point WHERE osm_id = 'node/4'
				""")).containsExactly("true");

		assertRejected(() -> execute(
				"INSERT INTO location_point (osm_id, category, geom, updated_at) VALUES ('node/5', 'heritage', "
						+ GEOM + ", NULL)"));
	}

	private void insert(String osmId, String category) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO location_point (osm_id, category, geom) VALUES (?, ?, " + GEOM + ")")) {
			bind(statement, osmId, category);
			statement.executeUpdate();
		}
	}

	/** The write shape M1-03's loader relies on. */
	private void upsert(String osmId, String category) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO location_point (osm_id, category, geom) VALUES (?, ?, " + GEOM + ") "
						+ "ON CONFLICT (osm_id) DO UPDATE SET category = EXCLUDED.category, updated_at = now()")) {
			bind(statement, osmId, category);
			statement.executeUpdate();
		}
	}

	private void bind(PreparedStatement statement, String osmId, String category) throws SQLException {
		setNullableString(statement, 1, osmId);
		setNullableString(statement, 2, category);
	}

	private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		}
		else {
			statement.setString(index, value);
		}
	}

	private void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private List<String> primaryKeyColumns() throws SQLException {
		return query("""
				SELECT a.attname
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				JOIN unnest(c.conkey) AS k(attnum) ON true
				JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
				WHERE n.nspname = 'public' AND t.relname = 'location_point' AND c.contype = 'p'
				""");
	}

	private void assertRejected(ThrowingRunnable action) throws SQLException {
		Savepoint savepoint = connection.setSavepoint();
		assertThatThrownBy(action::run).isInstanceOf(SQLException.class);
		connection.rollback(savepoint);
	}

	private List<String> query(String sql) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
			while (resultSet.next()) {
				rows.add(resultSet.getString(1));
			}
		}
		return rows;
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
