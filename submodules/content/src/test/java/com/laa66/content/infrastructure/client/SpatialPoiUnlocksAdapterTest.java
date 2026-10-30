package com.laa66.content.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;

/**
 * What the adapter does with each shape spatial can hand back. The mock replaces the request
 * factory, so the timeout and the bytes on the wire are proved separately by
 * {@link SpatialPoiUnlocksTimeoutTest} and {@link SpatialPoiUnlocksConnectionRefusedTest}.
 */
class SpatialPoiUnlocksAdapterTest {

	private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final String BASE_URL = "http://spatial:8080";
	private static final String OSM_ID = "node/42";

	private MockRestServiceServer spatial;
	private SpatialPoiUnlocksAdapter adapter;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.spatial = MockRestServiceServer.bindTo(builder).build();
		this.adapter = new SpatialPoiUnlocksAdapter(builder.build());
	}

	private void spatialResponds(ResponseCreator response) {
		spatial.expect(requestTo(BASE_URL + "/locations/node/42/unlocked"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("X-User-Id", USER.toString()))
				.andRespond(response);
	}

	private void assertUnavailable(ResponseCreator response) {
		spatialResponds(response);

		assertThatThrownBy(() -> adapter.isUnlocked(USER, OSM_ID))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);
		spatial.verify();
	}

	private static ResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	@Test
	void unlocked_isHonoured() {
		spatialResponds(json("{\"unlocked\":true}"));

		assertThat(adapter.isUnlocked(USER, OSM_ID)).isTrue();
		spatial.verify();
	}

	/** Also the shape of an unknown POI: spatial answers it with a 200, not a 404. */
	@Test
	void notUnlocked_isADefinitiveAnswer() {
		spatialResponds(json("{\"unlocked\":false}"));

		assertThat(adapter.isUnlocked(USER, OSM_ID)).isFalse();
		spatial.verify();
	}

	@Test
	void unknownFieldsAreIgnored() {
		spatialResponds(json("{\"unlocked\":true,\"grantedBy\":\"PROXIMITY\"}"));

		assertThat(adapter.isUnlocked(USER, OSM_ID)).isTrue();
		spatial.verify();
	}

	@Test
	void everyOsmElementTypeTravelsAsTwoSegments() {
		spatial.expect(requestTo(BASE_URL + "/locations/relation/7/unlocked"))
				.andRespond(json("{\"unlocked\":true}"));

		assertThat(adapter.isUnlocked(USER, "relation/7")).isTrue();
		spatial.verify();
	}

	@Test
	void serverError_isUnavailable() {
		assertUnavailable(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	@Test
	void serviceUnavailable_isUnavailable() {
		assertUnavailable(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
	}

	/** The route exists on every healthy spatial and never 404s on an unknown POI. */
	@Test
	void notFound_isUnavailableRatherThanNotUnlocked() {
		assertUnavailable(withStatus(HttpStatus.NOT_FOUND));
	}

	/** What spatial sends when {@code X-User-Id} goes missing: our bug, not a statement about the user. */
	@Test
	void badRequest_isUnavailable() {
		assertUnavailable(withStatus(HttpStatus.BAD_REQUEST));
	}

	@Test
	void forbidden_isUnavailableRatherThanNotUnlocked() {
		assertUnavailable(withStatus(HttpStatus.FORBIDDEN));
	}

	@Test
	void redirect_isUnavailable() {
		assertUnavailable(withStatus(HttpStatus.FOUND));
	}

	/** Spatial's route answers only 200, so any other status is not an answer, whatever its body says. */
	@ParameterizedTest
	@CsvSource({ "FOUND,true", "FOUND,false", "CREATED,true", "CREATED,false",
			"MULTIPLE_CHOICES,true", "MULTIPLE_CHOICES,false",
			"NON_AUTHORITATIVE_INFORMATION,true", "NON_AUTHORITATIVE_INFORMATION,false" })
	void aNon200CarryingAWellFormedBody_isUnavailable(HttpStatus status, boolean unlocked) {
		assertUnavailable(withStatus(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"unlocked\":" + unlocked + "}"));
	}

	/** Not a RestClientException: reading the header throws a bare IllegalArgumentException subclass. */
	@ParameterizedTest
	@CsvSource({ "OK,garbage", "OK,application/json;charset=bogus",
			"INTERNAL_SERVER_ERROR,garbage", "INTERNAL_SERVER_ERROR,application/json;charset=bogus",
			"SERVICE_UNAVAILABLE,garbage", "SERVICE_UNAVAILABLE,application/json;charset=bogus" })
	void anUnparsableContentType_isUnavailable(HttpStatus status, String contentType) {
		assertUnavailable(withStatus(status)
				.header(HttpHeaders.CONTENT_TYPE, contentType)
				.body("{\"unlocked\":true}"));
	}

	@Test
	void noContent_isUnavailable() {
		assertUnavailable(withStatus(HttpStatus.NO_CONTENT));
	}

	@Test
	void emptyBody_isUnavailable() {
		assertUnavailable(withSuccess());
	}

	@Test
	void jsonNullBody_isUnavailable() {
		assertUnavailable(json("null"));
	}

	@Test
	void fieldAbsent_isUnavailable() {
		assertUnavailable(json("{}"));
	}

	@Test
	void fieldExplicitlyNull_isUnavailable() {
		assertUnavailable(json("{\"unlocked\":null}"));
	}

	@Test
	void numberInsteadOfBoolean_isUnavailableRatherThanCoercedToFalse() {
		assertUnavailable(json("{\"unlocked\":0}"));
	}

	@Test
	void stringInsteadOfBoolean_isUnavailableRatherThanCoercedToFalse() {
		assertUnavailable(json("{\"unlocked\":\"false\"}"));
	}

	@Test
	void wrongJsonShape_isUnavailable() {
		assertUnavailable(json("[false]"));
	}

	@Test
	void malformedJson_isUnavailable() {
		assertUnavailable(json("{\"unlocked\": fa"));
	}

	@Test
	void nonJsonContentType_isUnavailable() {
		assertUnavailable(withSuccess("<html>bad gateway</html>", MediaType.TEXT_HTML));
	}

	@Test
	void transportFailure_isUnavailable_andKeepsItsCause() {
		spatialResponds(withException(new IOException("connection reset")));

		assertThatThrownBy(() -> adapter.isUnlocked(USER, OSM_ID))
				.isInstanceOf(PoiUnlockStateUnavailableException.class)
				.hasRootCauseMessage("connection reset");
		spatial.verify();
	}

	/**
	 * Request input. Each of these would reshape the URL or match another route, and none can name
	 * a POI, so the answer is "not unlocked" with nothing sent: the mock has no expectation, and any
	 * request at all would fail the test.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "", "node", "node/", "/42", "/", "node/42/extra", "../..", "node/..", "../42",
			"./42", "node/42?x=1", "node/42#f", "node%2F42", "node/4%32", "node/4 2", "node/{x}", "node/42\n" })
	void anIdThatCannotNameAPoi_isNotUnlocked_andIsNeverSent(String osmId) {
		assertThat(adapter.isUnlocked(USER, osmId)).isFalse();
		spatial.verify();
	}

	/** Only spatial's failures are "don't know": wrapping everything would hide this service's bugs. */
	@Test
	void aBugOnThisSideIsNotReportedAsAnOutage() {
		RestClient failing = RestClient.builder()
				.baseUrl(BASE_URL)
				.requestInterceptor((request, body, execution) -> {
					throw new IllegalStateException("bug");
				})
				.build();

		assertThatIllegalStateException()
				.isThrownBy(() -> new SpatialPoiUnlocksAdapter(failing).isUnlocked(USER, OSM_ID));
	}

	@Test
	void aMissingArgumentIsABug_notAnOutage() {
		assertThatNullPointerException().isThrownBy(() -> adapter.isUnlocked(null, OSM_ID));
		assertThatNullPointerException().isThrownBy(() -> adapter.isUnlocked(USER, null));
	}
}
