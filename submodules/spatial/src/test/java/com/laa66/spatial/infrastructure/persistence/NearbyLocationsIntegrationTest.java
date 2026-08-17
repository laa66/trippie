package com.laa66.spatial.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;

/**
 * Exercises the whole nearby slice — service + PostGIS adapter — over a real, Flyway-migrated
 * PostGIS container. Points are seeded with {@code ST_Project} so their distance from the query
 * center is exact on the same spheroid the query measures, making the 1m in/out boundary sound.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NearbyLocationsIntegrationTest extends AbstractPostgisIntegrationTest {

	// Wrocław: lat and lon are far apart in magnitude, so a lon/lat axis swap cannot pass silently.
	private static final double CENTER_LON = 17.03;
	private static final double CENTER_LAT = 51.11;
	private static final double NORTH = 0.0;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGIS::getUsername);
		registry.add("spring.datasource.password", POSTGIS::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	@Autowired
	private FindNearbyLocations findNearbyLocations;

	@Autowired
	private DataSource dataSource;

	private JdbcClient jdbc;

	@BeforeEach
	void resetTable() {
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE location_point").update();
	}

	@Test
	void returnsPoiJustInsideRadiusAndExcludesJustOutside() {
		seedProjected("node/inside", Category.MONUMENTS, 999, NORTH);
		seedProjected("node/outside", Category.MONUMENTS, 1001, NORTH);

		NearbyResult result = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(), 100));

		assertThat(result.items()).extracting(LocationPoint::osmId).containsExactly("node/inside");
		assertThat(result.items().get(0).distanceMeters()).isCloseTo(999.0, within(0.5));
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void appliesTheCategoryFilter() {
		seedProjected("node/museum", Category.MUSEUMS, 100, NORTH);
		seedProjected("node/sacred", Category.SACRED, 100, NORTH);
		seedProjected("node/monument", Category.MONUMENTS, 100, NORTH);

		NearbyResult result = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(Category.MUSEUMS), 100));

		assertThat(result.items()).extracting(LocationPoint::osmId).containsExactly("node/museum");
	}

	@Test
	void returnsRowsDistanceAscendingWithDistanceMeters() {
		// Seeded out of order to prove the DB sorts, not the insertion order.
		seedProjected("node/far", Category.HERITAGE, 500, NORTH);
		seedProjected("node/near", Category.HERITAGE, 100, NORTH);
		seedProjected("node/mid", Category.HERITAGE, 300, NORTH);

		NearbyResult result = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(), 100));

		assertThat(result.items()).extracting(LocationPoint::osmId)
				.containsExactly("node/near", "node/mid", "node/far");
		assertThat(result.items()).extracting(LocationPoint::distanceMeters)
				.isSortedAccordingTo(Double::compareTo);
		assertThat(result.items().get(0).distanceMeters()).isCloseTo(100.0, within(0.5));
		assertThat(result.items().get(2).distanceMeters()).isCloseTo(500.0, within(0.5));
	}

	@Test
	void capTruncatesAndReportsIt() {
		for (int i = 1; i <= 5; i++) {
			seedProjected("node/t" + i, Category.ATTRACTIONS, 100 + i, NORTH);
		}

		NearbyResult truncated = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(), 3));
		assertThat(truncated.items()).hasSize(3);
		assertThat(truncated.truncated()).isTrue();

		// Exactly at the cap is a full page, not a truncated one.
		NearbyResult exact = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(), 5));
		assertThat(exact.items()).hasSize(5);
		assertThat(exact.truncated()).isFalse();
	}

	@Test
	void extractsLonAsXAndLatAsYNotSwapped() {
		seedAt("node/center", Category.VIEWPOINTS, CENTER_LON, CENTER_LAT);

		NearbyResult result = findNearbyLocations.find(
				new NearbyQuery(CENTER_LAT, CENTER_LON, 1000, Set.of(), 100));

		assertThat(result.items()).hasSize(1);
		LocationPoint point = result.items().get(0);
		assertThat(point.lat()).isCloseTo(CENTER_LAT, within(1e-6));
		assertThat(point.lon()).isCloseTo(CENTER_LON, within(1e-6));
	}

	@Test
	void explainUsesGistIndexAndNoSeqScan() {
		// Enough spread points that the planner naturally prefers the GiST index for a tight
		// radius; a handful of rows would let it pick a seq scan and mask an index that is not
		// actually usable by the query as written.
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, category, geom)
				SELECT 'node/bulk-' || g, 'p' || g, 'monuments',
				       ST_SetSRID(ST_MakePoint(16.80 + random() * 0.38, 50.94 + random() * 0.27), 4326)::geography
				FROM generate_series(1, 5000) AS g
				""").update();
		jdbc.sql("ANALYZE location_point").update();

		String plan = String.join("\n", explainNearby(300));

		assertThat(plan).contains("idx_location_point_geom");
		assertThat(plan).doesNotContain("Seq Scan");
	}

	private List<String> explainNearby(int radius) {
		return jdbc.sql("EXPLAIN " + PostgisLocationAdapter.NEARBY_SQL)
				.param("lon", CENTER_LON)
				.param("lat", CENTER_LAT)
				.param("radius", radius)
				.param("categories", allCategoriesArray())
				.param("limit", 101)
				.query((rs, rowNum) -> rs.getString(1))
				.list();
	}

	private void seedAt(String osmId, Category category, double lon, double lat) {
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, category, geom)
				VALUES (:id, :name, :cat, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
				""")
				.param("id", osmId)
				.param("name", "name-" + osmId)
				.param("cat", category.slug())
				.param("lon", lon)
				.param("lat", lat)
				.update();
	}

	/** Places a point at an exact geodesic distance/azimuth from the query center. */
	private void seedProjected(String osmId, Category category, double distanceMeters, double azimuthRadians) {
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, category, geom)
				VALUES (:id, :name, :cat,
				        ST_Project(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :dist, :az))
				""")
				.param("id", osmId)
				.param("name", "name-" + osmId)
				.param("cat", category.slug())
				.param("lon", CENTER_LON)
				.param("lat", CENTER_LAT)
				.param("dist", distanceMeters)
				.param("az", azimuthRadians)
				.update();
	}

	private Array allCategoriesArray() {
		String[] slugs = java.util.Arrays.stream(Category.values()).map(Category::slug).toArray(String[]::new);
		try (Connection connection = dataSource.getConnection()) {
			return connection.createArrayOf("text", slugs);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
