package com.laa66.auth.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Array;
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
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.laa66.auth.support.AbstractPostgresIntegrationTest;

/**
 * Applies the real V1 migration to a database created clean for this test, so the assertions
 * describe what a virgin auth_db looks like after Flyway runs.
 *
 * <p>Assertions are written to fail on a weakened schema, not merely to pass on a correct one:
 * catalog introspection pins what a functional test cannot see — a ninth category slug, a dropped
 * NOT NULL, a dropped CHECK, a widened content-mode set — and each constraint is also exercised
 * with a bad row proving it actually rejects.
 */
class AuthMigrationIntTest extends AbstractPostgresIntegrationTest {

	private static final String DATABASE = "migration_check";

	private static final List<String> CATEGORY_SLUGS = List.of(
			"public_art", "monuments", "heritage", "sacred",
			"museums", "viewpoints", "architecture", "attractions");

	private static final List<String> CONTENT_MODES = List.of("TEXT", "AUDIO", "BOTH");

	private static MigrateResult migrateResult;
	private static Connection connection;

	@BeforeAll
	static void migrateCleanDatabase() throws SQLException {
		try (Connection admin = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = admin.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
			statement.execute("CREATE DATABASE " + DATABASE);
		}

		// No PostGIS or citext seeding here: unlike spatial's cluster-level postgis extension,
		// citext is trusted and the migration creates it itself, so a clean DB is all it needs.
		String url = jdbcUrlFor(DATABASE);
		migrateResult = Flyway.configure()
				.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();

		connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
		connection.setAutoCommit(false);
	}

	/** Keeps the container's own URL parameters (loggerLevel=OFF), swapping only the database. */
	private static String jdbcUrlFor(String database) {
		String url = POSTGRES.getJdbcUrl();
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
					assertThat(migration.description).isEqualTo("auth");
				});
	}

	// --- app_user -----------------------------------------------------------------------------

	@Test
	void appUserCarriesOnlyTheDedicatedColumns() throws SQLException {
		assertThat(columnNames("app_user")).containsExactlyInAnyOrder(
				"id", "email", "password_hash", "email_verified", "created_at");
	}

	@Test
	void idIsAUuidPrimaryKey() throws SQLException {
		assertThat(query("""
				SELECT udt_name || ':' || is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'app_user' AND column_name = 'id'
				""")).containsExactly("uuid:NO");

		assertThat(primaryKeyColumns("app_user")).containsExactly("id");
	}

	@Test
	void emailIsCaseInsensitiveAndUnique() throws SQLException {
		// CITEXT is what makes the UNIQUE constraint fold case; asserting the type pins that a
		// refactor to plain TEXT (which would silently allow User@x and user@x to coexist) fails.
		assertThat(query("""
				SELECT udt_name || ':' || is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'app_user' AND column_name = 'email'
				""")).containsExactly("citext:NO");

		insertUser("User@Example.com");
		assertRejected(() -> insertUser("user@example.com"));
	}

	@Test
	void passwordHashIsNotNull() throws SQLException {
		assertThat(nullability("app_user", "password_hash")).isEqualTo("NO");

		assertRejected(() -> insertUserRaw(UUID.randomUUID(), "nopass@example.com", null));
	}

	@Test
	void emailVerifiedIsNotNullAndDefaultsFalse() throws SQLException {
		assertThat(query("""
				SELECT data_type || ':' || is_nullable || ':' || coalesce(column_default, '<none>')
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'app_user' AND column_name = 'email_verified'
				""")).containsExactly("boolean:NO:false");

		UUID id = insertUser("fresh@example.com");
		assertThat(query("SELECT email_verified::text FROM app_user WHERE id = '" + id + "'"))
				.containsExactly("false");

		assertRejected(() -> execute(
				"INSERT INTO app_user (id, email, password_hash, email_verified) VALUES ("
						+ "'" + UUID.randomUUID() + "', 'v@example.com', 'x', NULL)"));
	}

	@Test
	void createdAtIsNotNullAndDefaultsToNow() throws SQLException {
		assertThat(query("""
				SELECT data_type || ':' || is_nullable || ':' || coalesce(column_default, '<none>')
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'app_user' AND column_name = 'created_at'
				""")).containsExactly("timestamp with time zone:NO:now()");

		UUID id = insertUser("clock@example.com");
		assertThat(query("""
				SELECT (created_at > now() - interval '1 minute')::text FROM app_user WHERE id = '"""
				+ id + "'")).containsExactly("true");
	}

	// --- user_settings ------------------------------------------------------------------------

	@Test
	void userSettingsCarriesOnlyTheDedicatedColumns() throws SQLException {
		assertThat(columnNames("user_settings")).containsExactlyInAnyOrder(
				"user_id", "default_content_mode", "selected_categories", "updated_at");
	}

	@Test
	void userIdIsThePrimaryKeyAndForeignKeyToAppUser() throws SQLException {
		assertThat(query("""
				SELECT udt_name || ':' || is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'user_settings' AND column_name = 'user_id'
				""")).containsExactly("uuid:NO");

		assertThat(primaryKeyColumns("user_settings")).containsExactly("user_id");

		// FK bites: settings for a user that does not exist are rejected.
		assertRejected(() -> insertSettings(UUID.randomUUID(), "BOTH", CATEGORY_SLUGS));

		// FK cascades: deleting the user removes its settings row.
		UUID id = insertUser("cascade@example.com");
		insertSettings(id, "BOTH", CATEGORY_SLUGS);
		execute("DELETE FROM app_user WHERE id = '" + id + "'");
		assertThat(query("SELECT count(*)::text FROM user_settings WHERE user_id = '" + id + "'"))
				.containsExactly("0");
	}

	@Test
	void contentModeCheckPinsExactlyTextAudioBoth() throws SQLException {
		assertThat(constraintLiterals("user_settings_content_mode_check"))
				.containsExactlyInAnyOrderElementsOf(CONTENT_MODES);

		UUID id = insertUser("mode@example.com");
		for (String mode : CONTENT_MODES) {
			UUID userId = insertUser(mode.toLowerCase() + "@example.com");
			assertThatCode(() -> insertSettings(userId, mode, CATEGORY_SLUGS))
					.as("content mode %s must be accepted", mode)
					.doesNotThrowAnyException();
		}

		assertRejected(() -> insertSettings(id, "VIDEO", CATEGORY_SLUGS));
	}

	@Test
	void contentModeIsNotNull() throws SQLException {
		// A CHECK with IN(...) passes on NULL (yields NULL, treated as satisfied), so NOT NULL is
		// the separate thing that rejects a missing mode.
		assertThat(nullability("user_settings", "default_content_mode")).isEqualTo("NO");

		UUID id = insertUser("nullmode@example.com");
		assertRejected(() -> insertSettings(id, null, CATEGORY_SLUGS));
	}

	@Test
	void selectedCategoriesCheckPinsExactlyTheEightSlugs() throws SQLException {
		assertThat(constraintLiterals("user_settings_categories_check"))
				.containsExactlyInAnyOrderElementsOf(CATEGORY_SLUGS);

		// The full seed set and any subset (including empty) are accepted.
		UUID all = insertUser("all@example.com");
		assertThatCode(() -> insertSettings(all, "BOTH", CATEGORY_SLUGS))
				.doesNotThrowAnyException();

		UUID subset = insertUser("subset@example.com");
		assertThatCode(() -> insertSettings(subset, "BOTH", List.of("museums", "monuments")))
				.doesNotThrowAnyException();

		UUID empty = insertUser("empty@example.com");
		assertThatCode(() -> insertSettings(empty, "BOTH", List.of()))
				.doesNotThrowAnyException();

		// A single unknown slug — and an unknown mixed in with valid ones — are both rejected.
		UUID bad = insertUser("bad@example.com");
		assertRejected(() -> insertSettings(bad, "BOTH", List.of("restaurants")));
		assertRejected(() -> insertSettings(bad, "BOTH", List.of("museums", "restaurants")));
	}

	@Test
	void selectedCategoriesIsNotNull() throws SQLException {
		assertThat(nullability("user_settings", "selected_categories")).isEqualTo("NO");

		UUID id = insertUser("nullcats@example.com");
		assertRejected(() -> insertSettings(id, "BOTH", null));
	}

	@Test
	void updatedAtIsNotNullAndDefaultsToNow() throws SQLException {
		assertThat(query("""
				SELECT data_type || ':' || is_nullable || ':' || coalesce(column_default, '<none>')
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'user_settings' AND column_name = 'updated_at'
				""")).containsExactly("timestamp with time zone:NO:now()");

		UUID id = insertUser("touched@example.com");
		insertSettings(id, "BOTH", CATEGORY_SLUGS);
		assertThat(query("""
				SELECT (updated_at > now() - interval '1 minute')::text
				FROM user_settings WHERE user_id = '""" + id + "'")).containsExactly("true");
	}

	// --- write helpers ------------------------------------------------------------------------

	private UUID insertUser(String email) throws SQLException {
		UUID id = UUID.randomUUID();
		insertUserRaw(id, email, "bcrypt-hash-placeholder");
		return id;
	}

	private void insertUserRaw(UUID id, String email, String passwordHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO app_user (id, email, password_hash) VALUES (?, ?, ?)")) {
			statement.setObject(1, id);
			statement.setString(2, email);
			if (passwordHash == null) {
				statement.setNull(3, Types.VARCHAR);
			}
			else {
				statement.setString(3, passwordHash);
			}
			statement.executeUpdate();
		}
	}

	private void insertSettings(UUID userId, String mode, List<String> categories) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO user_settings (user_id, default_content_mode, selected_categories) VALUES (?, ?, ?)")) {
			statement.setObject(1, userId);
			if (mode == null) {
				statement.setNull(2, Types.VARCHAR);
			}
			else {
				statement.setString(2, mode);
			}
			if (categories == null) {
				statement.setNull(3, Types.ARRAY);
			}
			else {
				Array array = connection.createArrayOf("text", categories.toArray());
				statement.setArray(3, array);
			}
			statement.executeUpdate();
		}
	}

	// --- introspection helpers ----------------------------------------------------------------

	private List<String> columnNames(String table) throws SQLException {
		return query("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = '""" + table + "'");
	}

	private String nullability(String table, String column) throws SQLException {
		return query("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = '""" + table
				+ "' AND column_name = '" + column + "'").get(0);
	}

	private List<String> primaryKeyColumns(String table) throws SQLException {
		return query("""
				SELECT a.attname
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				JOIN unnest(c.conkey) AS k(attnum) ON true
				JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
				WHERE n.nspname = 'public' AND t.relname = '""" + table + "' AND c.contype = 'p'");
	}

	/** The single-quoted string literals inside a named CHECK constraint's definition. */
	private List<String> constraintLiterals(String constraint) throws SQLException {
		return query("""
				SELECT unnest(regexp_matches(pg_get_constraintdef(oid), '''([^'']+)''::text', 'g'))
				FROM pg_constraint WHERE conname = '""" + constraint + "'");
	}

	// --- primitives ---------------------------------------------------------------------------

	private void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
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
