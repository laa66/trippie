package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.PoiContent;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.in.ReadPoiContent;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.domain.service.ReadPoiContentService;

/**
 * The real service behind the real controller and advice, with both outbound ports mocked: every
 * guarantee of this endpoint is an ordering between those two ports, so a mocked inbound port would
 * prove nothing.
 */
@WebMvcTest(ContentController.class)
@Import(ContentControllerTest.RealService.class)
class ContentControllerTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");
	private static final String POI = "node/42";
	private static final PoiContentKey KEY = new PoiContentKey(POI, ContentMode.TEXT, "pl");

	@TestConfiguration
	static class RealService {

		@Bean
		ReadPoiContent readPoiContent(PoiUnlocks poiUnlocks, ContentRepository contentRepository) {
			return new ReadPoiContentService(poiUnlocks, contentRepository);
		}
	}

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PoiUnlocks poiUnlocks;

	@MockitoBean
	private ContentRepository contentRepository;

	private static MockHttpServletRequestBuilder contentRequest() {
		return get("/content").header("X-User-Id", USER.toString())
				.queryParam("poiId", POI)
				.queryParam("mode", "TEXT");
	}

	private ResultActions readWithRow(PoiContent row) throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(true);
		when(contentRepository.find(KEY)).thenReturn(Optional.ofNullable(row));
		return mvc.perform(contentRequest());
	}

	@Test
	void completedServesStatusAndText() throws Exception {
		readWithRow(new PoiContent(ContentStatus.COMPLETED, "Przewodnik", null))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"status": "COMPLETED", "text": "Przewodnik"}
						""", JsonCompareMode.STRICT));
	}

	@Test
	void processingServesStatusAlone() throws Exception {
		readWithRow(new PoiContent(ContentStatus.PROCESSING, null, null))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"status": "PROCESSING"}
						""", JsonCompareMode.STRICT));
	}

	@Test
	void failedServesStatusAndClass() throws Exception {
		readWithRow(new PoiContent(ContentStatus.FAILED, null, FailedClass.QUOTA))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"status": "FAILED", "failedClass": "QUOTA"}
						""", JsonCompareMode.STRICT));
	}

	@Test
	void noRowIsAbsentAndNotA404() throws Exception {
		readWithRow(null)
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"status": "ABSENT"}
						""", JsonCompareMode.STRICT));
	}

	/** The table allows both rows; the client contract ties {@code text} to {@code COMPLETED}. */
	@Test
	void textLeftFromAnEarlierSuccessIsServedOnlyOnCompleted() throws Exception {
		readWithRow(new PoiContent(ContentStatus.PROCESSING, "stara treść", null))
				.andExpect(content().json("""
						{"status": "PROCESSING"}
						""", JsonCompareMode.STRICT));
		readWithRow(new PoiContent(ContentStatus.FAILED, "stara treść", FailedClass.TRANSIENT))
				.andExpect(content().json("""
						{"status": "FAILED", "failedClass": "TRANSIENT"}
						""", JsonCompareMode.STRICT));
	}

	@Test
	void theGateRunsBeforeTheReadAndTheKeyIsServerLang() throws Exception {
		readWithRow(null);

		InOrder order = inOrder(poiUnlocks, contentRepository);
		order.verify(poiUnlocks).isUnlocked(USER, POI);
		order.verify(contentRepository).find(KEY);
	}

	@Test
	void aLockedPoiIsA403AndTheContentIsNeverRead() throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(false);

		mvc.perform(contentRequest())
				.andExpect(status().isForbidden())
				.andExpect(content().contentType("application/problem+json"))
				.andExpect(content().json("""
						{"title": "Forbidden", "status": 403, "detail": "POI is not unlocked",
						 "instance": "/content"}
						""", JsonCompareMode.STRICT));

		verifyNoInteractions(contentRepository);
	}

	@Test
	void the403IsIdenticalWhetherOrNotContentExists() throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(false);

		when(contentRepository.find(KEY)).thenReturn(Optional.empty());
		MockHttpServletResponse withoutContent = mvc.perform(contentRequest()).andReturn().getResponse();

		when(contentRepository.find(KEY))
				.thenReturn(Optional.of(new PoiContent(ContentStatus.COMPLETED, "Przewodnik", null)));
		MockHttpServletResponse withContent = mvc.perform(contentRequest()).andReturn().getResponse();

		assertThat(withContent.getStatus()).isEqualTo(403).isEqualTo(withoutContent.getStatus());
		assertThat(withContent.getContentAsByteArray()).isEqualTo(withoutContent.getContentAsByteArray());
		assertThat(headers(withContent)).isEqualTo(headers(withoutContent));
	}

	private static Map<String, List<String>> headers(MockHttpServletResponse response) {
		Map<String, List<String>> headers = new HashMap<>();
		response.getHeaderNames().forEach(name -> headers.put(name, response.getHeaders(name)));
		return headers;
	}

	@Test
	void anOutageIsA503WithAFixedBodyAndNeverReadsContent() throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenThrow(new PoiUnlockStateUnavailableException(
				"spatial message", new IllegalStateException("http://spatial:8080 cause")));

		String body = mvc.perform(contentRequest())
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("""
						{"title": "Service Unavailable", "status": 503,
						 "detail": "Unlock state is temporarily unavailable", "instance": "/content"}
						""", JsonCompareMode.STRICT))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("spatial message", "cause", "spatial:8080");
		verifyNoInteractions(contentRepository);
	}

	@ParameterizedTest
	@ValueSource(strings = { "AUDIO", "BOTH" })
	void aModeThatIsNotServedIsA422BeforeSpatialIsAsked(String mode) throws Exception {
		mvc.perform(get("/content").header("X-User-Id", USER.toString())
						.queryParam("poiId", POI)
						.queryParam("mode", mode))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().json("""
						{"status": 422, "detail": "Only TEXT content is available"}
						"""));

		verifyNoInteractions(poiUnlocks, contentRepository);
	}

	@Test
	void anAbsentUserHeaderIsA400AndNotAnAnonymousRead() throws Exception {
		mvc.perform(get("/content").queryParam("poiId", POI).queryParam("mode", "TEXT"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository);
	}

	@Test
	void aMalformedUserHeaderIsA400() throws Exception {
		mvc.perform(get("/content").header("X-User-Id", "not-a-uuid")
						.queryParam("poiId", POI)
						.queryParam("mode", "TEXT"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository);
	}

	@Test
	void anAbsentPoiIdIsA400() throws Exception {
		mvc.perform(get("/content").header("X-User-Id", USER.toString()).queryParam("mode", "TEXT"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository);
	}

	/**
	 * An empty {@code poiId} satisfies {@code @RequestParam}: the parameter is present. It reaches the
	 * gate like any id that cannot name a POI, and the real adapter's refusal of it without a call is
	 * pinned end to end in {@code ContentReadIntegrationTest}.
	 */
	@Test
	void anEmptyPoiIdReachesTheGateAsAnEmptyId() throws Exception {
		when(poiUnlocks.isUnlocked(USER, "")).thenReturn(false);

		mvc.perform(get("/content").header("X-User-Id", USER.toString())
						.queryParam("poiId", "")
						.queryParam("mode", "TEXT"))
				.andExpect(status().isForbidden());

		verify(poiUnlocks).isUnlocked(USER, "");
		verifyNoInteractions(contentRepository);
	}

	@Test
	void anAbsentModeIsA400() throws Exception {
		mvc.perform(get("/content").header("X-User-Id", USER.toString()).queryParam("poiId", POI))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "VIDEO", "text" })
	void anEmptyOrUnknownModeIsA400(String mode) throws Exception {
		mvc.perform(get("/content").header("X-User-Id", USER.toString())
						.queryParam("poiId", POI)
						.queryParam("mode", mode))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository);
	}
}
