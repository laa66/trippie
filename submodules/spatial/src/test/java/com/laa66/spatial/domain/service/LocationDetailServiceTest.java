package com.laa66.spatial.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.port.out.LocationRepository;

class LocationDetailServiceTest {

	private final LocationRepository repository = mock(LocationRepository.class);

	private final LocationDetailService service = new LocationDetailService(repository);

	@Test
	void theIdIsPassedThroughUntouched() {
		LocationDetail detail = new LocationDetail(
				"node/42", "Ratusz", Category.HERITAGE, "Gothic town hall.", Map.of("wikidata", "Q123"));
		when(repository.findById("node/42")).thenReturn(Optional.of(detail));

		assertThat(service.find("node/42")).contains(detail);
	}

	/**
	 * An unknown id stays empty rather than becoming an exception here: the status code is the web
	 * layer's decision, and this service has no policy of its own to apply.
	 */
	@Test
	void anUnknownIdStaysEmpty() {
		when(repository.findById("node/999")).thenReturn(Optional.empty());

		assertThat(service.find("node/999")).isEmpty();
	}
}
