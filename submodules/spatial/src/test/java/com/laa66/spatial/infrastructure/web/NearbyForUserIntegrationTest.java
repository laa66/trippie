package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * {@code GET /locations/nearby} with the {@code unlocked} decoration, over a real PostGIS container
 * and the shipped wiring: the unlock is created by the real {@code POST /locations/unlock}, not by
 * an insert, so what these assertions observe is the same row the product writes.
 *
 * <p>Only {@link UserPreferences} is replaced, exactly as {@link UnlockEndpointIntegrationTest}
 * does, so the unlock half needs no auth service.
 *
 * <p>The decoration's shape is unit-tested in {@code NearbyLocationsForUserServiceTest} and its
 * structural guard is {@code CacheableNearbyContractTest}; what only a database can show is here:
 * that the flag is scoped to one user's rows, and that writing one changes nothing about the answer
 * the cacheable port gives.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NearbyForUserIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

	private static final double OBSERVER_LAT = 51.11;
	private static final double OBSERVER_LON = 17.03;

	private static final String POI_NEAR = "node/at-10m";
	private static final String POI_FAR = "node/at-400m";

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

	/** The cacheable port itself, to observe directly what a cache would have stored. */
	@Autowired
	private FindNearbyLocations findNearbyLocations;

	@MockitoBean
	private UserPreferences userPreferences;

	private PoiSeeder poi;

	@BeforeEach
	void resetTables() {
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiAtDistance(POI_NEAR, 10);
		poi.seedPoiAtDistance(POI_FAR, 400);
		when(userPreferences.requireProximity(USER_A)).thenReturn(true);
		when(userPreferences.requireProximity(USER_B)).thenReturn(true);
	}

	@Test
	void anUntouchedPoiIsReportedLocked() throws Exception {
		mvc.perform(nearbyAs(USER_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(POI_NEAR))
				.andExpect(jsonPath("$.items[0].unlocked").value(false));
	}

	/**
	 * <strong>The security assertion of this task.</strong> One user's unlock must not appear in
	 * another user's response — the failure the M6 cache key would have caused, and the one a
	 * single-user test cannot see. Both users are asked in the same test, about the same POI, with
	 * the same query.
	 */
	@Test
	void anUnlockByUserAIsNotVisibleToUserB() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(nearbyAs(USER_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(POI_NEAR))
				.andExpect(jsonPath("$.items[0].unlocked").value(true));

		mvc.perform(nearbyAs(USER_B))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(POI_NEAR))
				.andExpect(jsonPath("$.items[0].unlocked").value(false));
	}

	/**
	 * <strong>What makes the M6 cache safe, demonstrated rather than reasoned about:</strong> an
	 * unlock changes nothing about the value the cacheable port returns, so caching that value
	 * across users — which the frozen key does — cannot serve a stale or foreign unlock state.
	 * Equality over the whole result, not a field-by-field check, so a new per-user field would
	 * break this too and not only the structural guard.
	 */
	@Test
	void anUnlockDoesNotChangeWhatTheCacheablePortReturns() throws Exception {
		NearbyResult before = findNearbyLocations.find(query(1000, Set.of()));

		// Without this, the test is satisfiable by comparing two empty results -- measured: narrowing
		// the radius to 1 m in both calls left it green. The POI the unlock is about has to be in
		// there for the comparison to mean anything.
		assertThat(before.items()).extracting(LocationPoint::osmId).contains(POI_NEAR);

		unlockAs(USER_A, POI_NEAR);

		assertThat(findNearbyLocations.find(query(1000, Set.of()))).isEqualTo(before);
	}

	/**
	 * Unlock state is a fact in the database, not a property of the view: it survives a category
	 * filter and a radius that would be a different cache entry entirely, because the decoration is
	 * applied to whatever page the geo query produced. A POI leaving and re-entering the viewport
	 * therefore comes back unlocked with no client-side bookkeeping.
	 */
	@Test
	void theFlagSurvivesADifferentRadiusAndACategoryFilter() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(get("/locations/nearby")
				.header("X-User-Id", USER_A.toString())
				.param("lat", String.valueOf(OBSERVER_LAT))
				.param("lon", String.valueOf(OBSERVER_LON))
				.param("radius", "50")
				.param("categories", Category.MONUMENTS.slug()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(POI_NEAR))
				.andExpect(jsonPath("$.items[0].unlocked").value(true));
	}

	/** Decoration is per item: the unlocked one is flagged, the one 400 m away is not. */
	@Test
	void onePageIsDecoratedPerItem() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(nearbyAs(USER_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(POI_NEAR))
				.andExpect(jsonPath("$.items[0].unlocked").value(true))
				.andExpect(jsonPath("$.items[1].id").value(POI_FAR))
				.andExpect(jsonPath("$.items[1].unlocked").value(false));
	}

	/**
	 * The header is required by the shipped wiring too, not just by the controller slice: an absent
	 * {@code X-User-Id} is the gateway misconfigured, and reporting every POI locked would hide it.
	 */
	@Test
	void aRequestWithoutTheUserHeaderIsRejected() throws Exception {
		mvc.perform(get("/locations/nearby")
				.param("lat", String.valueOf(OBSERVER_LAT))
				.param("lon", String.valueOf(OBSERVER_LON)))
				.andExpect(status().isBadRequest());
	}

	private void unlockAs(UUID userId, String osmId) throws Exception {
		mvc.perform(post("/locations/unlock")
				.header("X-User-Id", userId.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"lat":%s,"lon":%s,"accuracyMeters":12.5,"osmIds":["%s"]}"""
						.formatted(OBSERVER_LAT, OBSERVER_LON, osmId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(osmId));
	}

	private MockHttpServletRequestBuilder nearbyAs(UUID userId) {
		return get("/locations/nearby")
				.header("X-User-Id", userId.toString())
				.param("lat", String.valueOf(OBSERVER_LAT))
				.param("lon", String.valueOf(OBSERVER_LON));
	}

	private static NearbyQuery query(int radiusMeters, Set<Category> categories) {
		return new NearbyQuery(OBSERVER_LAT, OBSERVER_LON, radiusMeters, categories, 100);
	}
}
