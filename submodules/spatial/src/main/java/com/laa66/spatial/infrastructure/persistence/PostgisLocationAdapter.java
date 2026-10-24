package com.laa66.spatial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.port.out.LocationRepository;

/**
 * PostGIS adapter for the nearby read-path: Spring {@link JdbcClient} + native SQL, no ORM
 * (spatial has no write path — the Go loader is the only writer — and the {@code distance_m}
 * projection does not fit an entity).
 *
 * <p>{@code geom} is {@code geography(Point,4326)}, so {@code ST_DWithin} uses the plain GiST
 * index directly. {@code ST_Distance} on the same spheroid is both the sort key and the
 * projected {@code distanceMeters}, so the DB order can never desync from the reported number.
 * The category filter is a single {@code = ANY(?)} over a bound {@code text[]}: one plan, one
 * prepared statement, no {@code IN (...)} placeholder expansion.
 *
 * <p>The array is bound as a plain {@code String[]}, which pgjdbc infers as {@code text[]}.
 * Deliberately not a {@code java.sql.Array}: building one needs a {@link java.sql.Connection}, and
 * a {@code PgArray} keeps a reference to the physical connection it was created on, so returning
 * that connection to the pool and binding the array on a second checkout is a use-after-release
 * that only fails once the first connection is evicted. Deliberately not a {@code List} either —
 * {@code JdbcClient} expands that into placeholders and the {@code text[]} cast stops parsing.
 */
@Repository
public class PostgisLocationAdapter implements LocationRepository {

	static final String NEARBY_SQL = """
			SELECT osm_id,
			       name,
			       category,
			       ST_X(geom::geometry) AS lon,
			       ST_Y(geom::geometry) AS lat,
			       ST_Distance(geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS distance_m
			FROM location_point
			WHERE ST_DWithin(geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
			  AND category = ANY(:categories)
			ORDER BY distance_m ASC
			LIMIT :limit
			""";

	/**
	 * The by-id detail read, one statement and one round trip: the POI's own columns, plus its tags
	 * already flattened to scalar key/value text by the database.
	 *
	 * <p><strong>The flattening is done in SQL, and that is the decision in this method.</strong>
	 * {@code tags} is OSM's free-form JSONB, so a value can be an object or an array — the contract
	 * says those are dropped, not a 500. {@code jsonb_typeof} is the authoritative answer to "is this
	 * value a scalar", so the filter belongs where the type system is; the alternative, pulling the
	 * whole document out as text and walking it with a JSON library, would add a parse step, a second
	 * notion of "scalar" and a question about which Jackson is on a Boot 4 classpath, to arrive at the
	 * same map. {@code #>> '{}'} is the scalar-to-text projection, so a number becomes
	 * {@code "1912"} and a boolean {@code "true"}: the wire contract is string→string.
	 *
	 * <p>JSON {@code null} is excluded along with objects and arrays. It is a scalar type to
	 * {@code jsonb_typeof}, but admitting it would put a null <em>value</em> in a map documented as
	 * string→string, and "the tag is absent" is the same statement with no special case in it.
	 *
	 * <p>{@code LEFT JOIN LATERAL}, not an inner join: a POI with no tags — or with none that survive
	 * the filter — must still return its row, with one all-null tag pair that the mapper skips. An
	 * inner join would turn "no tags" into "no such POI", which is the 404 this endpoint owes to
	 * unknown ids only.
	 *
	 * <p><strong>The {@code CASE} is the same decision one level up, and it is not theoretical.</strong>
	 * {@code jsonb_each} is defined on objects alone: measured, {@code tags = '[1,2]'} raises
	 * {@code cannot call jsonb_each on a non-object}, and so do {@code 'null'} and {@code '"scalar"'} —
	 * an error from inside the read, surfacing as a 500 for a POI that is merely odd. The column's type
	 * permits all of them, so this normalises a non-object document to {@code '{}'} and the POI comes
	 * back with no tags, which is the same answer the per-value filter gives for a value it cannot use.
	 * Today's loader marshals a {@code map[string]string} and cannot produce one, but the schema is the
	 * contract here, not the current writer: {@code location_point} is written by another service in
	 * another language, which is exactly the boundary worth validating at.
	 */
	static final String DETAIL_SQL = """
			SELECT p.osm_id,
			       p.name,
			       p.description,
			       p.category,
			       t.key             AS tag_key,
			       t.value #>> '{}'  AS tag_value
			FROM location_point p
			LEFT JOIN LATERAL jsonb_each(
			         CASE WHEN jsonb_typeof(p.tags) = 'object' THEN p.tags ELSE '{}'::jsonb END
			     ) AS t(key, value)
			       ON jsonb_typeof(t.value) IN ('string', 'number', 'boolean')
			WHERE p.osm_id = :osmId
			""";

	private final JdbcClient jdbcClient;

	public PostgisLocationAdapter(DataSource dataSource) {
		this.jdbcClient = JdbcClient.create(dataSource);
	}

	@Override
	public NearbyResult findNearby(NearbyQuery query) {
		int limit = query.limit();

		// Fetch one past the cap so a full page is distinguishable from a truncated one.
		List<LocationPoint> rows = jdbcClient.sql(NEARBY_SQL)
				.param("lon", query.lon())
				.param("lat", query.lat())
				.param("radius", query.radiusMeters())
				.param("categories", categorySlugArray(query.categories()))
				.param("limit", limit + 1)
				.query(this::mapRow)
				.list();

		boolean truncated = rows.size() > limit;
		List<LocationPoint> items = truncated ? rows.subList(0, limit) : rows;
		return new NearbyResult(List.copyOf(items), truncated);
	}

	/**
	 * One row per surviving tag, so the result is folded here rather than aggregated in SQL: the POI's
	 * own columns repeat across those rows and the first one carries them all. A POI has tens of tags
	 * at most, which is why repeating four columns is cheaper than a second statement or a JSON
	 * aggregate that would need parsing back out.
	 */
	@Override
	public Optional<LocationDetail> findById(String osmId) {
		List<DetailRow> rows = jdbcClient.sql(DETAIL_SQL)
				.param("osmId", osmId)
				.query(this::mapDetailRow)
				.list();

		if (rows.isEmpty()) {
			return Optional.empty();
		}

		// LinkedHashMap, not Map.copyOf: jsonb normalises an object's key order when it stores the
		// document, so the query emits a stable one and the response keeps it. Map.copyOf randomises
		// iteration order per JVM, which would make the response bytes differ run to run for no reason.
		Map<String, String> tags = new LinkedHashMap<>();
		for (DetailRow row : rows) {
			if (row.tagKey() != null) {
				tags.put(row.tagKey(), row.tagValue());
			}
		}

		DetailRow first = rows.getFirst();
		return Optional.of(new LocationDetail(
				first.osmId(),
				first.name(),
				Category.fromSlug(first.categorySlug()),
				first.description(),
				Collections.unmodifiableMap(tags)));
	}

	private DetailRow mapDetailRow(ResultSet rs, int rowNum) throws SQLException {
		return new DetailRow(
				rs.getString("osm_id"),
				rs.getString("name"),
				rs.getString("description"),
				rs.getString("category"),
				rs.getString("tag_key"),
				rs.getString("tag_value"));
	}

	/** One joined row of the detail read: the POI's columns plus at most one tag pair. */
	private record DetailRow(
			String osmId,
			String name,
			String description,
			String categorySlug,
			String tagKey,
			String tagValue) {
	}

	private LocationPoint mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new LocationPoint(
				rs.getString("osm_id"),
				rs.getString("name"),
				Category.fromSlug(rs.getString("category")),
				rs.getDouble("lat"),
				rs.getDouble("lon"),
				rs.getDouble("distance_m"));
	}

	private String[] categorySlugArray(Set<Category> categories) {
		return categories.stream().map(Category::slug).toArray(String[]::new);
	}
}
