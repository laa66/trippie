package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.GenerationLease;
import com.laa66.content.domain.model.LeaseOutcome;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.in.GenerateContent;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.domain.service.GenerateContentService;

/**
 * The real service behind the real controller and advice, with the outbound ports mocked: the
 * response for every lease outcome, and the boundary's refusals.
 */
@WebMvcTest(ContentGenerationController.class)
@Import(ContentGenerationControllerTest.RealService.class)
class ContentGenerationControllerTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");
	private static final String POI = "node/42";
	private static final PoiContentKey KEY = new PoiContentKey(POI, ContentMode.TEXT, "pl");
	private static final Instant TOKEN = Instant.parse("2026-11-02T12:00:00.123456Z");

	@TestConfiguration
	static class RealService {

		@Bean
		GenerateContent generateContent(PoiUnlocks poiUnlocks, ContentRepository contentRepository,
				EventPublisher eventPublisher) {
			return new GenerateContentService(poiUnlocks, contentRepository, eventPublisher);
		}
	}

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PoiUnlocks poiUnlocks;

	@MockitoBean
	private ContentRepository contentRepository;

	@MockitoBean
	private EventPublisher eventPublisher;

	private ResultActions generate(String body) throws Exception {
		return mvc.perform(post("/content/generate").header("X-User-Id", USER.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions generateWith(LeaseOutcome outcome, Instant token) throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(true);
		when(contentRepository.leaseGeneration(KEY)).thenReturn(new GenerationLease(outcome, token));
		return generate("{\"poiId\":\"node/42\",\"mode\":\"TEXT\"}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "GRANTED", "RETRY_GRANTED" })
	void aGrantedLeaseIs202Processing(String outcome) throws Exception {
		generateWith(LeaseOutcome.valueOf(outcome), TOKEN)
				.andExpect(status().isAccepted())
				.andExpect(content().json("{\"status\":\"PROCESSING\"}", JsonCompareMode.STRICT));

		verify(eventPublisher).publish(new GenerateContentTask(KEY, TOKEN));
	}

	@Test
	void aHeldLeaseIs202ProcessingWithoutAPublish() throws Exception {
		generateWith(LeaseOutcome.HELD_BY_ANOTHER_ATTEMPT, null)
				.andExpect(status().isAccepted())
				.andExpect(content().json("{\"status\":\"PROCESSING\"}", JsonCompareMode.STRICT));

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void completedIs200WithStatusAloneAndNeverText() throws Exception {
		generateWith(LeaseOutcome.ALREADY_COMPLETED, null)
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"COMPLETED\"}", JsonCompareMode.STRICT));

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void anExhaustedBudgetIs200Failed() throws Exception {
		generateWith(LeaseOutcome.ATTEMPTS_EXHAUSTED, null)
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"FAILED\"}", JsonCompareMode.STRICT));

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void aPublishTheBrokerDidNotTakeIsA503ThatSaysNothingAboutTheBroker() throws Exception {
		doThrow(new EventPublicationException("broker refused the publish",
				new IllegalStateException("amqp://rabbitmq:5672 refused")))
				.when(eventPublisher).publish(any());

		String body = generateWith(LeaseOutcome.GRANTED, TOKEN)
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentType("application/problem+json"))
				.andExpect(content().json("""
						{"title": "Service Unavailable", "status": 503,
						 "detail": "Content generation is temporarily unavailable", "instance": "/content/generate"}
						""", JsonCompareMode.STRICT))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("broker", "amqp", "rabbitmq");
		verify(contentRepository).releaseUnpublishedLease(KEY, TOKEN);
	}

	@Test
	void aLockedPoiIs403() throws Exception {
		when(poiUnlocks.isUnlocked(USER, POI)).thenReturn(false);

		generate("{\"poiId\":\"node/42\",\"mode\":\"TEXT\"}")
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"status\":403,\"detail\":\"POI is not unlocked\"}"));

		verifyNoInteractions(contentRepository, eventPublisher);
	}

	@ParameterizedTest
	@ValueSource(strings = { "AUDIO", "BOTH" })
	void aModeThatIsNotServedIs422(String mode) throws Exception {
		generate("{\"poiId\":\"node/42\",\"mode\":\"" + mode + "\"}")
				.andExpect(status().isUnprocessableContent());

		verifyNoInteractions(poiUnlocks, contentRepository, eventPublisher);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"mode\":\"TEXT\"}", "{\"poiId\":\"node/42\"}", "{\"poiId\":null,\"mode\":\"TEXT\"}",
			"{\"poiId\":\"node/42\",\"mode\":\"VIDEO\"}", "{\"poiId\":\"node/42\",\"mode\":\"text\"}",
			"{\"poiId\":\"node/42\",\"mode\":\"\"}", "{}", "", "[]" })
	void anIncompleteOrUnreadableBodyIs400(String body) throws Exception {
		generate(body).andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository, eventPublisher);
	}

	@Test
	void anAbsentUserHeaderIs400() throws Exception {
		mvc.perform(post("/content/generate").contentType(MediaType.APPLICATION_JSON)
						.content("{\"poiId\":\"node/42\",\"mode\":\"TEXT\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(poiUnlocks, contentRepository, eventPublisher);
	}
}
