package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * The unlock endpoint with the real auth adapter and no auth behind it: nothing listens on the
 * configured port, so every preference lookup is refused. The endpoint must then behave exactly as
 * it does for {@code requireProximity = true} — the gate applied, the distant POI refused, the row
 * absent — because a lenient degradation would mint permanent {@code EXEMPT} rows for a user who
 * never asked to be exempt, and there is no re-lock to take them back.
 *
 * <p>Its sibling {@link UnlockEndpointIntegrationTest} replaces the port with a mock to drive the
 * toggle; this one keeps the shipped wiring and takes auth away instead, which is what makes the
 * fail-closed answer an assertion about the endpoint rather than about the adapter alone.
 *
 * <p><strong>One failure mode, not both.</strong> A closed port means an immediate
 * {@code ConnectException}, so what is covered here is connection-refused and nothing else. The
 * other way a lookup fails — the timeout, where auth accepts the connection and then does not
 * answer within the one-second budget — is covered where the budget actually lives, in
 * {@code AuthUserPreferencesTimeoutTest} against a blackholed address. Both end at the same strict
 * answer, but only that test can prove the clock.
 *
 * <p>A separate class because the auth base URI is fixed when the bean is built, so one context
 * cannot have both a working preference lookup and a dead one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnlockFailClosedIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");

	private static final double OBSERVER_LAT = 51.11;
	private static final double OBSERVER_LON = 17.03;

	private static final String POI_49M = "node/at-49m";
	private static final String POI_5KM = "node/at-5km";

	private static final int CLOSED_PORT = reserveThenReleasePort();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGIS::getUsername);
		registry.add("spring.datasource.password", POSTGIS::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("trippie.auth.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + CLOSED_PORT);
	}

	/** Lets the OS pick a free port, then closes the socket so connecting to it is refused. */
	private static int reserveThenReleasePort() {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	private JdbcClient jdbc;

	private PoiSeeder poi;

	@BeforeEach
	void resetTables() {
		jdbc = JdbcClient.create(dataSource);
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiAtDistance(POI_49M, 49);
		poi.seedPoiAtDistance(POI_5KM, 5000);
	}

	@Test
	void withAuthDownADistantPoiIsRefusedAndNoRowIsWritten() throws Exception {
		mvc.perform(unlock(12.5, POI_5KM))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").isEmpty())
				.andExpect(jsonPath("$.refused[0]").value(POI_5KM));

		assertThat(rowCount()).isZero();
	}

	@Test
	void withAuthDownTheAccuracyGateStillApplies() throws Exception {
		mvc.perform(unlock(101, POI_49M))
				.andExpect(status().isUnprocessableEntity());

		assertThat(rowCount()).isZero();
	}

	/**
	 * The strict answer is strict, not broken: a genuinely nearby POI still unlocks while auth is
	 * down, and the row records {@code PROXIMITY} — the measured source, which is the only one an
	 * unavailable preference can justify.
	 */
	@Test
	void withAuthDownANearbyPoiStillUnlocksAsProximity() throws Exception {
		mvc.perform(unlock(12.5, POI_49M))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value(POI_49M));

		assertThat(jdbc.sql("SELECT granted_by FROM poi_unlock WHERE user_id = :user AND osm_id = :poi")
				.param("user", USER)
				.param("poi", POI_49M)
				.query(String.class)
				.single()).isEqualTo("PROXIMITY");
	}

	private MockHttpServletRequestBuilder unlock(double accuracyMeters, String osmId) {
		return post("/locations/unlock")
				.header("X-User-Id", USER.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"lat\":%s,\"lon\":%s,\"accuracyMeters\":%s,\"osmIds\":[\"%s\"]}"
						.formatted(OBSERVER_LAT, OBSERVER_LON, accuracyMeters, osmId));
	}

	private int rowCount() {
		return jdbc.sql("SELECT count(*) FROM poi_unlock").query(Integer.class).single();
	}
}
