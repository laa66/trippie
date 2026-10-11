package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * {@code POST /locations/unlock} end to end over a real PostGIS container: the HTTP contract, the
 * geometric gate and the rows it does or does not leave behind, all in one place, because the value
 * of this endpoint is precisely that those three agree.
 *
 * <p>Only {@link UserPreferences} is replaced, so the toggle can be driven from a test without an
 * auth service; everything from the controller down to the SQL is the shipped wiring. The
 * fail-closed half of the preference read is covered by {@link UnlockFailClosedIntegrationTest},
 * which keeps the real adapter and takes auth away instead.
 *
 * <p>POIs are placed by {@link PoiSeeder} at exact geodesic distances from one observer point, so
 * the 49 m / 51 m pair is produced by the same spheroid that evaluates {@code ST_DWithin}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnlockEndpointIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final UUID USER = UUID.fromString("55555555-5555-4555-8555-555555555555");
	private static final UUID OTHER_USER = UUID.fromString("66666666-6666-4666-8666-666666666666");

	private static final double OBSERVER_LAT = 51.11;
	private static final double OBSERVER_LON = 17.03;

	private static final String POI_49M = "node/at-49m";
	private static final String POI_51M = "node/at-51m";
	private static final String POI_5KM = "node/at-5km";
	private static final String UNKNOWN = "node/does-not-exist";

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGIS::getUsername);
		registry.add("spring.datasource.password", POSTGIS::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private UserPreferences userPreferences;

	private JdbcClient jdbc;

	private PoiSeeder poi;

	@BeforeEach
	void resetTables() {
		jdbc = JdbcClient.create(dataSource);
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiAtDistance(POI_49M, 49);
		poi.seedPoiAtDistance(POI_51M, 51);
		poi.seedPoiAtDistance(POI_5KM, 5000);
		requireProximity(true);
	}

	// --- the radius ---------------------------------------------------------------------------

	@Test
	void aPoi49mAwayIsUnlockedAndRecordedAsProximity() throws Exception {
		mvc.perform(unlock(12.5, POI_49M))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_49M))
				.andExpect(jsonPath("$.refused").isEmpty());

		assertThat(grantedBy(USER, POI_49M)).isEqualTo("PROXIMITY");
	}

	@Test
	void aPoi51mAwayIsRefusedAndLeavesNoRow() throws Exception {
		mvc.perform(unlock(12.5, POI_51M))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").isEmpty())
				.andExpect(jsonPath("$.refused[0]").value(POI_51M));

		assertThat(rowCount()).isZero();
	}

	/** One position claim, one radius: the batch is split by distance, not accepted or rejected whole. */
	@Test
	void oneBatchIsPartitionedByDistance() throws Exception {
		mvc.perform(unlock(12.5, POI_49M, POI_51M, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked", Matchers.contains(POI_49M)))
				.andExpect(jsonPath("$.refused", Matchers.containsInAnyOrder(POI_51M, POI_5KM)));

		assertThat(rowCount()).isEqualTo(1);
	}

	// --- the accuracy gate --------------------------------------------------------------------

	@Test
	void accuracyAtTheThresholdUnlocks() throws Exception {
		mvc.perform(unlock(100, POI_49M))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_49M));

		assertThat(rowCount()).isEqualTo(1);
	}

	@Test
	void accuracyPastTheThresholdIs422AndWritesNothing() throws Exception {
		mvc.perform(unlock(101, POI_49M))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.status").value(422));

		assertThat(rowCount()).isZero();
	}

	@Test
	void aMissingAccuracyIs400AndWritesNothing() throws Exception {
		mvc.perform(body("""
				{"lat":51.11,"lon":17.03,"osmIds":["%s"]}""".formatted(POI_49M)))
				.andExpect(status().isBadRequest());

		assertThat(rowCount()).isZero();
	}

	// --- the toggle ----------------------------------------------------------------------------

	/**
	 * The one path on which the distance predicate does not apply, and the reason the row records
	 * {@code EXEMPT}: there is no re-lock, so a row granted because the user waived proximity can
	 * never be told apart from a measured one afterwards unless it is written down now.
	 */
	@Test
	void aWaivedProximityRequirementUnlocksFrom5kmAndRecordsItAsExempt() throws Exception {
		requireProximity(false);

		mvc.perform(unlock(12.5, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_5KM));

		assertThat(grantedBy(USER, POI_5KM)).isEqualTo("EXEMPT");
	}

	/** The accuracy gate is part of proximity enforcement, so waiving the requirement lifts it too. */
	@Test
	void aWaivedProximityRequirementAlsoLiftsTheAccuracyGate() throws Exception {
		requireProximity(false);

		mvc.perform(unlock(150, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_5KM));

		assertThat(grantedBy(USER, POI_5KM)).isEqualTo("EXEMPT");
	}

	// --- existence, idempotency, state ---------------------------------------------------------

	@Test
	void anUnknownOsmIdIsRefusedWhileItsBatchMateIsStillUnlocked() throws Exception {
		mvc.perform(unlock(12.5, POI_49M, UNKNOWN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked", Matchers.contains(POI_49M)))
				.andExpect(jsonPath("$.refused", Matchers.contains(UNKNOWN)));

		assertThat(rowCount()).isEqualTo(1);
	}

	/**
	 * The clarification that {@code refused} means strictly "too far away, or no such POI": a POST
	 * repeated by a client that has not yet refreshed must report the POI as unlocked, not as
	 * refused, and must not add a second row.
	 */
	@Test
	void aRepeatedPostLeavesOneRowAndStillReportsThePoiAsUnlocked() throws Exception {
		mvc.perform(unlock(12.5, POI_49M)).andExpect(status().isOk());

		mvc.perform(unlock(12.5, POI_49M))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_49M))
				.andExpect(jsonPath("$.refused").isEmpty());

		assertThat(rowCount()).isEqualTo(1);
	}

	/** Walking away does not retract a fact, so an already-unlocked POI is not refused from 5 km off. */
	@Test
	void anAlreadyUnlockedPoiIsReportedFromOutsideTheRadius() throws Exception {
		requireProximity(false);
		mvc.perform(unlock(12.5, POI_5KM)).andExpect(status().isOk());
		requireProximity(true);

		mvc.perform(unlock(12.5, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_5KM))
				.andExpect(jsonPath("$.refused").isEmpty());

		assertThat(rowCount()).isEqualTo(1);
	}

	/** Another user's unlock is not this user's: the read-back is scoped to the subject. */
	@Test
	void anotherUsersUnlockIsNotReportedAsThisUsers() throws Exception {
		requireProximity(false);
		mvc.perform(unlockAs(OTHER_USER, 12.5, POI_5KM)).andExpect(status().isOk());
		requireProximity(true);

		mvc.perform(unlock(12.5, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").isEmpty())
				.andExpect(jsonPath("$.refused[0]").value(POI_5KM));

		assertThat(rowCount()).isEqualTo(1);
	}

	// --- boundary rejections, proven against the table -----------------------------------------

	@Test
	void anEmptyOsmIdsIs400AndWritesNothing() throws Exception {
		mvc.perform(body("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":[]}"""))
				.andExpect(status().isBadRequest());

		assertThat(rowCount()).isZero();
	}

	/** 51 ids, one past the cap of 50; the cap itself is pinned against the constant in the slice test. */
	@Test
	void osmIdsPastTheCapIs400AndWritesNothing() throws Exception {
		String ids = IntStream.range(0, 51)
				.mapToObj(i -> "\"node/%d\"".formatted(i))
				.collect(Collectors.joining(","));

		mvc.perform(body("{\"lat\":51.11,\"lon\":17.03,\"accuracyMeters\":12.5,\"osmIds\":[" + ids + "]}"))
				.andExpect(status().isBadRequest());

		assertThat(rowCount()).isZero();
	}

	/**
	 * Without the boundary check a null id reaches the insert as a NOT NULL violation: safe, nothing
	 * written, but reported as a 500. Asserted end to end because that is where the wrong status came
	 * from.
	 */
	@Test
	void aNullOsmIdIs400NotA500() throws Exception {
		mvc.perform(body("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":[null]}"""))
				.andExpect(status().isBadRequest());

		assertThat(rowCount()).isZero();
	}

	/**
	 * The null id's twin, and asserted end to end for the same reason: without the boundary check
	 * an embedded NUL reaches the {@code text[]} bind, which Postgres rejects — safe, nothing
	 * written, but reported as a 500 for a plainly malformed request.
	 */
	@Test
	void anOsmIdCarryingANulByteIs400NotA500() throws Exception {
		mvc.perform(body("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["a\\u0000b"]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		assertThat(rowCount()).isZero();
	}

	/**
	 * An unbounded accuracy is not a measured one. The service's 100 m threshold cannot be what
	 * catches this, because an exempt user is excused from that threshold and would unlock from a
	 * position whose stated accuracy is infinite — so the boundary rejects it for both kinds of user.
	 */
	@Test
	void anInfiniteAccuracyIs400EvenForAnExemptUser() throws Exception {
		requireProximity(false);

		mvc.perform(body("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":1e400,"osmIds":["%s"]}""".formatted(POI_49M)))
				.andExpect(status().isBadRequest());

		assertThat(rowCount()).isZero();
	}

	private void requireProximity(boolean required) {
		when(userPreferences.requireProximity(USER)).thenReturn(required);
		when(userPreferences.requireProximity(OTHER_USER)).thenReturn(required);
	}

	private MockHttpServletRequestBuilder unlock(double accuracyMeters, String... osmIds) {
		return unlockAs(USER, accuracyMeters, osmIds);
	}

	private MockHttpServletRequestBuilder unlockAs(UUID userId, double accuracyMeters, String... osmIds) {
		String ids = Stream.of(osmIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
		return post("/locations/unlock")
				.header("X-User-Id", userId.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"lat\":%s,\"lon\":%s,\"accuracyMeters\":%s,\"osmIds\":[%s]}"
						.formatted(OBSERVER_LAT, OBSERVER_LON, accuracyMeters, ids));
	}

	private MockHttpServletRequestBuilder body(String json) {
		return post("/locations/unlock")
				.header("X-User-Id", USER.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private int rowCount() {
		return jdbc.sql("SELECT count(*) FROM poi_unlock").query(Integer.class).single();
	}

	private String grantedBy(UUID userId, String osmId) {
		return jdbc.sql("SELECT granted_by FROM poi_unlock WHERE user_id = :user AND osm_id = :poi")
				.param("user", userId)
				.param("poi", osmId)
				.query(String.class)
				.single();
	}
}
