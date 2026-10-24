package com.laa66.spatial.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.model.NearbyResultForUser;
import com.laa66.spatial.domain.model.UnlockResult;
import com.laa66.spatial.domain.port.in.CheckLocationUnlocked;
import com.laa66.spatial.domain.port.in.FindLocationDetail;
import com.laa66.spatial.domain.port.in.FindNearbyLocationsForUser;
import com.laa66.spatial.domain.port.in.UnlockLocations;

/**
 * <strong>Which controller a {@code /locations/*} request reaches, pinned rather than trusted.</strong>
 * Every controller in the module is loaded here — the slice tests each load one, so none of them can
 * see a request going to the wrong one.
 *
 * <p>The risk M4-01 had to answer is a by-id read swallowing the two established routes: if the
 * detail endpoint ever matches {@code /locations/nearby}, the map stops loading and the symptom is a
 * POI lookup for a POI called "nearby". Two things keep that from happening and only one of them is
 * visible in the code. The first is Spring's pattern precedence, which prefers a literal segment to
 * a template. The second — the real one here — is that {@code osm_id} is {@code "<type>/<id>"}, so
 * the detail routes are two and three segments long while {@code /locations/nearby} and
 * {@code /locations/unlock} are one: they cannot overlap at all. That property is a consequence of
 * the id format, which is the Go loader's decision and not spatial's, so it is asserted here rather
 * than assumed to hold forever.
 *
 * <p>Both directions are checked, because "the request reached a controller" is not the claim —
 * "it reached <em>that</em> controller and no other port was touched" is.
 */
@WebMvcTest
class LocationRoutingTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private FindNearbyLocationsForUser findNearbyLocations;

	@MockitoBean
	private UnlockLocations unlockLocations;

	@MockitoBean
	private FindLocationDetail findLocationDetail;

	@MockitoBean
	private CheckLocationUnlocked checkLocationUnlocked;

	@Test
	void nearbyStillReachesTheNearbyController() throws Exception {
		when(findNearbyLocations.find(any(), any())).thenReturn(new NearbyResultForUser(List.of(), false));

		mvc.perform(get("/locations/nearby")
				.header("X-User-Id", USER.toString())
				.param("lat", "51.11")
				.param("lon", "17.03"))
				.andExpect(status().isOk());

		verify(findNearbyLocations).find(any(), any());
		verifyNoInteractions(findLocationDetail, checkLocationUnlocked, unlockLocations);
	}

	@Test
	void unlockStillReachesTheUnlockController() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of("node/42"), Set.of()));

		mvc.perform(post("/locations/unlock")
				.header("X-User-Id", USER.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/42"]}"""))
				.andExpect(status().isOk());

		verify(unlockLocations).unlock(any());
		verifyNoInteractions(findNearbyLocations, findLocationDetail, checkLocationUnlocked);
	}

	@Test
	void anOsmIdReachesTheDetailController() throws Exception {
		when(findLocationDetail.find("node/42")).thenReturn(Optional.of(
				new LocationDetail("node/42", "Ratusz", Category.HERITAGE, null, Map.of())));

		mvc.perform(get("/locations/node/42")).andExpect(status().isOk());

		verify(findLocationDetail).find("node/42");
		verifyNoInteractions(findNearbyLocations, checkLocationUnlocked, unlockLocations);
	}

	@Test
	void theUnlockedSuffixReachesTheUnlockStateController() throws Exception {
		when(checkLocationUnlocked.isUnlocked(any(), anyString())).thenReturn(true);

		mvc.perform(get("/locations/node/42/unlocked").header("X-User-Id", USER.toString()))
				.andExpect(status().isOk());

		verify(checkLocationUnlocked).isUnlocked(USER, "node/42");
		verifyNoInteractions(findNearbyLocations, findLocationDetail, unlockLocations);
	}

	/**
	 * The other direction: a POI id whose first segment happens to be {@code nearby} is a detail
	 * lookup for {@code "nearby/42"} — not a nearby query missing its parameters, which is what a
	 * greedier detail pattern or a looser nearby mapping would turn it into. It 404s, because no such
	 * POI exists, and that is the honest answer.
	 */
	@Test
	void aLiteralRouteNameInsideAnIdDoesNotHijackTheRequest() throws Exception {
		when(findLocationDetail.find("nearby/42")).thenReturn(Optional.empty());

		mvc.perform(get("/locations/nearby/42")).andExpect(status().isNotFound());

		verify(findLocationDetail).find("nearby/42");
		verifyNoInteractions(findNearbyLocations, checkLocationUnlocked, unlockLocations);
	}
}
