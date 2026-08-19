package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;

@WebMvcTest(NearbyLocationsController.class)
class NearbyLocationsControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private FindNearbyLocations findNearbyLocations;

	@Test
	void okResponse_hasFrozenItemShape() throws Exception {
		LocationPoint point = new LocationPoint("node/123", "Some POI", Category.SACRED, 51.1, 17.0, 42.5);
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(point), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.items[0].id").value("node/123"))
				.andExpect(jsonPath("$.items[0].name").value("Some POI"))
				.andExpect(jsonPath("$.items[0].category").value("sacred"))
				.andExpect(jsonPath("$.items[0].lat").value(51.1))
				.andExpect(jsonPath("$.items[0].lon").value(17.0))
				.andExpect(jsonPath("$.items[0].distanceMeters").value(42.5))
				.andExpect(jsonPath("$.items[0].description").doesNotExist())
				.andExpect(jsonPath("$.items[0].tags").doesNotExist());
	}

	@Test
	void truncatedFlag_isPassedThrough() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), true));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	void omittedParams_applyDocumentedDefaults() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		NearbyQuery query = captor.getValue();

		assertThat(query.lat()).isEqualTo(51.1);
		assertThat(query.lon()).isEqualTo(17.0);
		assertThat(query.radiusMeters()).isEqualTo(1000);
		assertThat(query.limit()).isEqualTo(100);
		assertThat(query.categories()).isEmpty();
	}

	@Test
	void categoriesCsv_isParsedToSlugs() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=sacred,museums"))
				.andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEqualTo(Set.of(Category.SACRED, Category.MUSEUMS));
	}

	// --- boundary inclusion (lat/lon/radius/limit) ---------------------------------------

	@Test
	void latAtMax90_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));
		mvc.perform(get("/locations/nearby?lat=90&lon=17.0")).andExpect(status().isOk());
	}

	@Test
	void latAtMinNeg90_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));
		mvc.perform(get("/locations/nearby?lat=-90&lon=17.0")).andExpect(status().isOk());
	}

	@Test
	void lonAtMax180_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));
		mvc.perform(get("/locations/nearby?lat=51.1&lon=180")).andExpect(status().isOk());
	}

	@Test
	void lonAtMinNeg180_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));
		mvc.perform(get("/locations/nearby?lat=51.1&lon=-180")).andExpect(status().isOk());
	}

	@Test
	void radiusAtMax5000_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&radius=5000")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().radiusMeters()).isEqualTo(5000);
	}

	@Test
	void limitAtMax500_isAccepted() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&limit=500")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().limit()).isEqualTo(500);
	}

	@Test
	void latBelowMin_is400ProblemDetail() throws Exception {
		mvc.perform(get("/locations/nearby?lat=-91&lon=17.0"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void latAboveMax_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=91&lon=17.0")).andExpect(status().isBadRequest());
	}

	@Test
	void lonBelowMin_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=-181")).andExpect(status().isBadRequest());
	}

	@Test
	void lonAboveMax_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=181")).andExpect(status().isBadRequest());
	}

	@Test
	void radiusAboveMax_is400ProblemDetail() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&radius=5001"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void limitAboveMax_is400ProblemDetail() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&limit=501"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void nonPositiveRadius_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&radius=0")).andExpect(status().isBadRequest());
	}

	@Test
	void nonPositiveLimit_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&limit=-1")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownCategorySlug_is400ProblemDetail() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=not-a-category"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void malformedNumericParam_is400ProblemDetail() throws Exception {
		mvc.perform(get("/locations/nearby?lat=not-a-number&lon=17.0"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void missingRequiredParams_is400() throws Exception {
		mvc.perform(get("/locations/nearby")).andExpect(status().isBadRequest());
	}

	// --- lenient CSV categories: blank tokens dropped, only a non-blank unknown slug rejects ---

	@Test
	void categoriesCsv_withSpaceAfterComma_isParsed() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=sacred, museums")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEqualTo(Set.of(Category.SACRED, Category.MUSEUMS));
	}

	@Test
	void categoriesCsv_withTrailingComma_isParsed() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=sacred,")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEqualTo(Set.of(Category.SACRED));
	}

	@Test
	void categoriesCsv_withDoubledComma_isParsed() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=sacred,,museums")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEqualTo(Set.of(Category.SACRED, Category.MUSEUMS));
	}

	@Test
	void categoriesCsv_withLeadingComma_isParsed() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=,sacred")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEqualTo(Set.of(Category.SACRED));
	}

	@Test
	void categoriesEmptyString_resolvesToEmptySet() throws Exception {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));

		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=")).andExpect(status().isOk());

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue().categories()).isEmpty();
	}

	@Test
	void categoriesCsv_withNonBlankUnknownSlug_is400() throws Exception {
		mvc.perform(get("/locations/nearby?lat=51.1&lon=17.0&categories=sacred,bogus"))
				.andExpect(status().isBadRequest());
	}
}
