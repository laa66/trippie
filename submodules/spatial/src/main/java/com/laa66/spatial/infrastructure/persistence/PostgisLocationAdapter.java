package com.laa66.spatial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.laa66.spatial.domain.model.Category;
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
