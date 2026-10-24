package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.port.in.FindLocationDetail;

@WebMvcTest(LocationDetailController.class)
class LocationDetailControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private FindLocationDetail findLocationDetail;

	/**
	 * The wire shape, compared strictly so the key set is frozen and not only the values: a sixth
	 * field appearing here — {@code unlocked} being the one that would be wrong — fails this test.
	 */
	@Test
	void okResponse_hasFrozenShape() throws Exception {
		when(findLocationDetail.find("node/42")).thenReturn(Optional.of(detail()));

		mvc.perform(get("/locations/node/42"))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{
						  "id": "node/42",
						  "name": "Ratusz",
						  "category": "heritage",
						  "description": "Gothic town hall.",
						  "tags": {"wikidata": "Q123", "start_date": "1299"}
						}
						""", JsonCompareMode.STRICT));
	}

	/**
	 * {@code osm_id} is {@code "<type>/<id>"}, so the id the port is asked about must be the two
	 * segments joined back together. A controller reading only {@code {osmRef}} would look up
	 * {@code "42"}, find nothing and 404 every real POI — green against a stub that answers
	 * {@code any()}, which is why this asserts the captured value.
	 */
	@Test
	void theIdIsReassembledFromBothPathSegments() throws Exception {
		when(findLocationDetail.find(any())).thenReturn(Optional.of(detail()));

		mvc.perform(get("/locations/way/7")).andExpect(status().isOk());

		ArgumentCaptor<String> osmId = ArgumentCaptor.forClass(String.class);
		verify(findLocationDetail).find(osmId.capture());
		assertThat(osmId.getValue()).isEqualTo("way/7");
	}

	/**
	 * <strong>The point of this endpoint.</strong> No {@code X-User-Id}, and a 200 anyway: the read
	 * is user-free, which is what makes it cacheable and what lets the content worker — which has no
	 * user — read a POI without asserting an identity it does not have.
	 */
	@Test
	void theReadNeedsNoUserHeader() throws Exception {
		when(findLocationDetail.find("node/42")).thenReturn(Optional.of(detail()));

		mvc.perform(get("/locations/node/42"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("node/42"));
	}

	// The structural half of "user-free" — no header, no subject, no ambient identity — lives in
	// UserFreeDetailReadContractTest: a @RequestHeader screen alone was measured to be blind to an
	// HttpServletRequest parameter, and the nets that close that are not Spring-slice concerns.

	/** A header sent anyway is simply not read: it has no way to change the answer. */
	@Test
	void anUnsolicitedUserHeaderChangesNothing() throws Exception {
		when(findLocationDetail.find("node/42")).thenReturn(Optional.of(detail()));

		mvc.perform(get("/locations/node/42").header("X-User-Id", UUID.randomUUID().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("node/42"));
	}

	/** An unknown POI is a 404 here — existence is this endpoint's question, unlike the unlock read. */
	@Test
	void anUnknownIdIsNotFound() throws Exception {
		when(findLocationDetail.find("node/999")).thenReturn(Optional.empty());

		mvc.perform(get("/locations/node/999")).andExpect(status().isNotFound());
	}

	/**
	 * The 404 body: the module's ProblemDetail shape, a <em>constant</em> {@code detail}, and
	 * {@code instance} carrying the request URI.
	 *
	 * <p>Both halves are asserted because both are decisions. The constant detail is ours — a handler
	 * building {@code "no POI " + id} would fail here. The echoed instance is Spring's default for a
	 * null {@code instance}, kept deliberately (see
	 * {@code SpatialRequestExceptionHandler#handleLocationNotFound}) and pinned so that a future
	 * {@code setInstance(URI.create("about:blank"))} is a visible change rather than a silent one.
	 * An earlier version of this test asserted only status and detail while being named for a property
	 * of the whole body, which is the kind of green that reads as coverage.
	 */
	@Test
	void theNotFoundBodyKeepsDetailConstantAndEchoesTheRequestUriAsInstance() throws Exception {
		when(findLocationDetail.find(any())).thenReturn(Optional.empty());

		mvc.perform(get("/locations/node/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("No POI with that id"))
				.andExpect(jsonPath("$.instance").value("/locations/node/999"));
	}

	/** No tags is an empty object, not a missing key: the key set must not depend on the data. */
	@Test
	void aPoiWithoutTagsStillCarriesTheKey() throws Exception {
		when(findLocationDetail.find("node/42")).thenReturn(Optional.of(new LocationDetail(
				"node/42", "Ratusz", Category.HERITAGE, null, Map.of())));

		mvc.perform(get("/locations/node/42"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags").isEmpty())
				.andExpect(content().json("""
						{"id": "node/42", "name": "Ratusz", "category": "heritage",
						 "description": null, "tags": {}}
						""", JsonCompareMode.STRICT));
	}

	private static LocationDetail detail() {
		Map<String, String> tags = new LinkedHashMap<>();
		tags.put("wikidata", "Q123");
		tags.put("start_date", "1299");
		return new LocationDetail("node/42", "Ratusz", Category.HERITAGE, "Gothic town hall.", tags);
	}
}
