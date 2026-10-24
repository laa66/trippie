package com.laa66.spatial.support;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.laa66.spatial.domain.model.Category;

/**
 * Seeds {@code location_point} rows at exact geodesic distances from one observer point, for every
 * test whose assertions are about the unlock radius.
 *
 * <p>{@code ST_Project} places each POI on the same WGS84 spheroid that {@code ST_DWithin}
 * evaluates, so the 49 m / 51 m pair the tests reason about is produced by the geometry engine
 * rather than by hand-converted degrees.
 *
 * <p>Shared rather than copied per test class on purpose: the distance table every unlock test
 * depends on is only as trustworthy as this one method, and
 * {@code PoiUnlockIntegrationTest#seedPlacesPoisAtTheIntendedGeodesicDistances} pins exactly this
 * copy. Three private copies meant two of them were pinned by nothing.
 */
public final class PoiSeeder {

	private final JdbcClient jdbc;
	private final double observerLat;
	private final double observerLon;

	public PoiSeeder(DataSource dataSource, double observerLat, double observerLon) {
		this.jdbc = JdbcClient.create(dataSource);
		this.observerLat = observerLat;
		this.observerLon = observerLon;
	}

	/** Truncates both unlock tables; {@code poi_unlock} first, so the FK never objects. */
	public void reset() {
		jdbc.sql("TRUNCATE poi_unlock, location_point").update();
	}

	/** Places the POI exactly {@code meters} due north of the observer, on the same spheroid. */
	public void seedPoiAtDistance(String osmId, double meters) {
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, category, geom)
				VALUES (:id, :name, :cat,
				        ST_Project(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :meters, radians(0)))
				""")
				.param("id", osmId)
				.param("name", "name-" + osmId)
				.param("cat", Category.MONUMENTS.slug())
				.param("lon", observerLon)
				.param("lat", observerLat)
				.param("meters", meters)
				.update();
	}

	/**
	 * The same placement, plus the two columns only the by-id detail read projects:
	 * {@code description} and the raw {@code tags} document.
	 *
	 * <p>{@code tagsJson} is bound as text and cast, so a test can seed whatever OSM's mappers can
	 * write — a nested object, an array, a JSON null — and the assertions are then about what the
	 * query does with real JSONB rather than about what a Java-side flattener would have done.
	 * Separate from {@link #seedPoiAtDistance} rather than an extra parameter on it: that method is
	 * what every unlock-radius test's distance table rests on, and
	 * {@code PoiUnlockIntegrationTest#seedPlacesPoisAtTheIntendedGeodesicDistances} pins exactly it.
	 */
	public void seedPoiWithDetail(String osmId, double meters, String name, String description, String tagsJson) {
		jdbc.sql("""
				INSERT INTO location_point (osm_id, name, description, category, geom, tags)
				VALUES (:id, :name, :description, :cat,
				        ST_Project(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :meters, radians(0)),
				        CAST(:tags AS jsonb))
				""")
				.param("id", osmId)
				.param("name", name)
				.param("description", description)
				.param("cat", Category.HERITAGE.slug())
				.param("lon", observerLon)
				.param("lat", observerLat)
				.param("meters", meters)
				.param("tags", tagsJson)
				.update();
	}

	/** What the geometry engine itself says the seeded POI's distance from the observer is. */
	public double distanceFromObserver(String osmId) {
		return jdbc.sql("""
				SELECT ST_Distance(geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
				FROM location_point WHERE osm_id = :id
				""")
				.param("lon", observerLon)
				.param("lat", observerLat)
				.param("id", osmId)
				.query(Double.class)
				.single();
	}
}
