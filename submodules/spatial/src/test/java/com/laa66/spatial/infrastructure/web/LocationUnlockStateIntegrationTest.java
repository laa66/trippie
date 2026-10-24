package com.laa66.spatial.infrastructure.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * {@code GET /locations/{osmId}/unlocked} over a real PostGIS container and the shipped wiring. The
 * unlock it reads is created by the real {@code POST /locations/unlock}, not by an insert, so the row
 * these assertions observe is the one the product writes — the precedent
 * {@link NearbyForUserIntegrationTest} set. Only {@link UserPreferences} is replaced, so the write
 * half needs no auth service.
 *
 * <p>What only a database can show is the scoping: this endpoint answers about one user's rows, and
 * a single-user test cannot tell that apart from an endpoint that answers about anybody's.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationUnlockStateIntegrationTest extends AbstractPostgisIntegrationTest {

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

	@MockitoBean
	private UserPreferences userPreferences;

	private PoiSeeder poi;

	@BeforeEach
	void seed() {
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiAtDistance(POI_NEAR, 10);
		poi.seedPoiAtDistance(POI_FAR, 400);
		when(userPreferences.requireProximity(USER_A)).thenReturn(true);
		when(userPreferences.requireProximity(USER_B)).thenReturn(true);
	}

	@Test
	void anUntouchedPoiReadsAsLocked() throws Exception {
		mvc.perform(unlockedAs(USER_A, POI_NEAR))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").value(false));
	}

	@Test
	void aGrantedPoiReadsAsUnlocked() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(unlockedAs(USER_A, POI_NEAR))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").value(true));
	}

	/**
	 * <strong>The security assertion of this endpoint.</strong> Both users, same POI, same request
	 * shape: A's grant must not be B's. This is the guard the M3-05 review measured to be invisible
	 * to {@code unitTest}, now on a second read path — dropping {@code user_id} from
	 * {@code FIND_UNLOCKED_SQL} would leave the service unit tests green and fail here.
	 */
	@Test
	void anUnlockByUserAIsNotVisibleToUserB() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(unlockedAs(USER_A, POI_NEAR))
				.andExpect(jsonPath("$.unlocked").value(true));
		mvc.perform(unlockedAs(USER_B, POI_NEAR))
				.andExpect(jsonPath("$.unlocked").value(false));
	}

	/** One grant is about one POI: the other seeded POI stays locked for the same user. */
	@Test
	void aGrantDoesNotUnlockANeighbouringPoi() throws Exception {
		unlockAs(USER_A, POI_NEAR);

		mvc.perform(unlockedAs(USER_A, POI_FAR))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").value(false));
	}

	/**
	 * <strong>Not a 404, deliberately.</strong> "Not unlocked" is true of a POI that does not exist,
	 * and a 404 would make this endpoint an existence oracle whose answer disagrees with the detail
	 * endpoint's for no benefit. Content refuses either way.
	 */
	@Test
	void anUnknownPoiIsNotUnlockedRatherThanNotFound() throws Exception {
		mvc.perform(unlockedAs(USER_A, "node/999"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").value(false));
	}

	/** Required by the shipped wiring too: an absent header is a broken gateway, not an anonymous read. */
	@Test
	void aRequestWithoutTheUserHeaderIsRejected() throws Exception {
		mvc.perform(get("/locations/" + POI_NEAR + "/unlocked"))
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

	private MockHttpServletRequestBuilder unlockedAs(UUID userId, String osmId) {
		return get("/locations/" + osmId + "/unlocked").header("X-User-Id", userId.toString());
	}
}
