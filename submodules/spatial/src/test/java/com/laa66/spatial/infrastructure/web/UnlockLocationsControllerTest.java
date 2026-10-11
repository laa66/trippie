package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.spatial.domain.model.InsufficientAccuracyException;
import com.laa66.spatial.domain.model.UnlockAttempt;
import com.laa66.spatial.domain.model.UnlockResult;
import com.laa66.spatial.domain.port.in.UnlockLocations;

/**
 * The unlock boundary without a database: what the wire shape is, what is rejected before any
 * policy runs, and how a policy refusal is reported. Runs under {@code unitTest}.
 */
@WebMvcTest(UnlockLocationsController.class)
class UnlockLocationsControllerTest {

	private static final String USER_ID = "44444444-4444-4444-8444-444444444444";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UnlockLocations unlockLocations;

	// --- the happy path and the wire shape -------------------------------------------------

	@Test
	void okResponse_partitionsIntoUnlockedAndRefused() throws Exception {
		when(unlockLocations.unlock(any()))
				.thenReturn(new UnlockResult(Set.of("node/a"), Set.of("node/b")));

		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a","node/b"]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked[0]").value("node/a"))
				.andExpect(jsonPath("$.refused[0]").value("node/b"))
				// No reason codes: the accuracy case is a whole-request failure, not a refused id.
				.andExpect(jsonPath("$.reasons").doesNotExist());
	}

	@Test
	void theAssertedPositionAndSubjectReachTheUseCase() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of(), Set.of()));

		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isOk());

		UnlockAttempt attempt = capturedAttempt();
		assertThat(attempt.userId()).isEqualTo(UUID.fromString(USER_ID));
		assertThat(attempt.lat()).isEqualTo(51.11);
		assertThat(attempt.lon()).isEqualTo(17.03);
		assertThat(attempt.accuracyMeters()).isEqualTo(12.5);
		assertThat(attempt.osmIds()).containsExactly("node/a");
	}

	/** The body is a JSON list, so the same id can arrive twice; the boundary collapses it. */
	@Test
	void aRepeatedIdIsDeDuplicatedBeforeItLeavesTheBoundary() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of(), Set.of()));

		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a","node/a","node/b"]}"""))
				.andExpect(status().isOk());

		assertThat(capturedAttempt().osmIds()).containsExactly("node/a", "node/b");
	}

	@Test
	void osmIdsAtTheCapIsAccepted() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of(), Set.of()));

		mvc.perform(unlockRequest(bodyWithIdCount(UnlockLocationsController.MAX_OSM_IDS)))
				.andExpect(status().isOk());

		assertThat(capturedAttempt().osmIds()).hasSize(UnlockLocationsController.MAX_OSM_IDS);
	}

	// --- the subject ------------------------------------------------------------------------

	/**
	 * The header is bound as a UUID, which is what keeps a crafted value out of the {@code X-User-Id}
	 * spatial itself sends to auth: a String subject carrying a newline would inject a header there.
	 */
	@Test
	void aMalformedUserIdHeaderIs400AndNeverReachesTheUseCase() throws Exception {
		mvc.perform(post("/locations/unlock")
				.header("X-User-Id", "not-a-uuid\r\nX-Injected: 1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	@Test
	void aMissingUserIdHeaderIs400RatherThanAnAnonymousUnlock() throws Exception {
		mvc.perform(post("/locations/unlock")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	// --- boundary rejections -----------------------------------------------------------------

	@Test
	void aMissingAccuracyIs400ProblemDetail() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));

		verifyNoInteractions(unlockLocations);
	}

	@Test
	void aNullAccuracyIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":null,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aNonPositiveAccuracyIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":0,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":-1,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Infinity is {@code > 0}, so a bare positivity check lets it past. That matters because the
	 * service's 100 m threshold is not a backstop for it: the threshold is skipped for an exempt
	 * user, so the grant would go through carrying an accuracy of "unbounded" — an unmeasured
	 * position, which is the one thing this field exists to exclude. Both notations Jackson accepts
	 * are covered, and the NaN case with them: it is neither finite nor positive.
	 */
	@Test
	void aNonFiniteAccuracyIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":1e400,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":"Infinity","osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":"NaN","osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	/**
	 * A coarse fix is NOT a 400 here: it is well-formed and in range, and whether it is good enough
	 * is a policy question the service answers. Pinned so the boundary cannot quietly grow a second
	 * accuracy gate that would shadow the 422.
	 */
	@Test
	void aCoarseButPositiveAccuracyPassesTheBoundary() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of(), Set.of("node/a")));

		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":5000,"osmIds":["node/a"]}"""))
				.andExpect(status().isOk());
	}

	@Test
	void aMissingOrOutOfRangeLatOrLonIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":91,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":-181,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	/**
	 * The one test that pins the negated-range form of the lat/lon check. Jackson coerces the JSON
	 * string {@code "NaN"} into a {@code Double}, so this body is reachable from the wire — and
	 * rewriting the check to the readable {@code lat < -90 || lat > 90} was measured to survive the
	 * entire rest of the suite while letting NaN through, because NaN compares false against both
	 * bounds.
	 *
	 * <p>What the gate buys is this 400 rather than a silently empty 200: {@code ST_DWithin} against
	 * a NaN point returns false, so a NaN would refuse every id and look like "nothing nearby".
	 */
	@Test
	void aNaNLatOrLonIs400AndNeverReachesTheUseCase() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":"NaN","lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":"NaN","accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	/**
	 * An infinite lat or lon is out of range by the same comparison, in both notations Jackson
	 * accepts — an overflowing literal and the string form.
	 */
	@Test
	void anInfiniteLatOrLonIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":1e400,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":"-Infinity","accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	@Test
	void latAndLonAtTheirBoundsAreAccepted() throws Exception {
		when(unlockLocations.unlock(any())).thenReturn(new UnlockResult(Set.of(), Set.of()));

		mvc.perform(unlockRequest("""
				{"lat":90,"lon":180,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isOk());
		mvc.perform(unlockRequest("""
				{"lat":-90,"lon":-180,"accuracyMeters":12.5,"osmIds":["node/a"]}"""))
				.andExpect(status().isOk());
	}

	@Test
	void anEmptyOrMissingOsmIdsIs400() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":[]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	@Test
	void osmIdsPastTheCapIs400() throws Exception {
		mvc.perform(unlockRequest(bodyWithIdCount(UnlockLocationsController.MAX_OSM_IDS + 1)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		verifyNoInteractions(unlockLocations);
	}

	/**
	 * Without this check a null id reaches the insert as a NOT NULL violation — safe, nothing is
	 * written, but reported as a 500 for what is plainly a malformed request.
	 */
	@Test
	void aNullOrBlankOsmIdIs400RatherThan500() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":[null]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a",null]}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["  "]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	/**
	 * The same 400-not-500 lesson as the null id, for the other value that cannot survive the bind:
	 * an embedded NUL is neither null nor blank, so it used to reach the {@code text[]} parameter,
	 * which Postgres rejects. It is the only such character — everything else in a parsed JSON
	 * string round-trips through {@code text} — and it is a bound parameter either way, never SQL.
	 */
	@Test
	void anOsmIdCarryingANulByteIs400RatherThan500() throws Exception {
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["a\\u0000b"]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(400));
		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":12.5,"osmIds":["node/a","\\u0000"]}"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(unlockLocations);
	}

	@Test
	void anUnreadableBodyIs400ProblemDetail() throws Exception {
		mvc.perform(unlockRequest("not json"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")));
	}

	// --- policy refusal ----------------------------------------------------------------------

	/** 422 and not 400: the request is syntactically fine and semantically impossible to honour. */
	@Test
	void insufficientAccuracyIs422ProblemDetail() throws Exception {
		when(unlockLocations.unlock(any())).thenThrow(new InsufficientAccuracyException("too coarse"));

		mvc.perform(unlockRequest("""
				{"lat":51.11,"lon":17.03,"accuracyMeters":101,"osmIds":["node/a"]}"""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
				.andExpect(jsonPath("$.status").value(422))
				.andExpect(jsonPath("$.detail").value("too coarse"));
	}

	private MockHttpServletRequestBuilder unlockRequest(String body) {
		return post("/locations/unlock")
				.header("X-User-Id", USER_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private String bodyWithIdCount(int count) {
		String ids = IntStream.range(0, count)
				.mapToObj(i -> "\"node/" + i + "\"")
				.collect(Collectors.joining(","));
		return "{\"lat\":51.11,\"lon\":17.03,\"accuracyMeters\":12.5,\"osmIds\":[" + ids + "]}";
	}

	private UnlockAttempt capturedAttempt() {
		ArgumentCaptor<UnlockAttempt> captor = ArgumentCaptor.forClass(UnlockAttempt.class);
		verify(unlockLocations).unlock(captor.capture());
		return captor.getValue();
	}
}
