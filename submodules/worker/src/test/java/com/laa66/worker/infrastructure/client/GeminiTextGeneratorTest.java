package com.laa66.worker.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.support.GeminiStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against a local stub on a real socket; no test here, or anywhere in the build, calls
 * the real API. The shipped 20 s timeout is proved in {@code GeminiProviderTimeoutTest}; here a
 * short one keeps the stall cases fast.
 */
class GeminiTextGeneratorTest {

	/** Fake, and shaped like a real key so a leak would look like one. */
	static final String FAKE_KEY = "AIzaFAKE-only-for-tests-0123456789abcdef";

	private static final String MODEL = "gemini-3.5-flash-lite";

	private static final int MAX_OUTPUT_TOKENS = 1024;

	private static final PoiPrompt PROMPT = new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl");

	private static final JsonMapper JSON = JsonMapper.shared();

	private final GeminiStub stub = new GeminiStub();

	@AfterEach
	void stopStub() {
		stub.close();
	}

	private GeminiTextGenerator generator(String baseUrl, Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
		factory.setReadTimeout(timeout);
		RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		return new GeminiTextGenerator(client, new GeminiApiKey(FAKE_KEY), MODEL, MAX_OUTPUT_TOKENS);
	}

	private GeminiTextGenerator generator() {
		return generator(stub.baseUrl(), Duration.ofSeconds(2));
	}

	private TextGenerationException failureOf(GeminiTextGenerator generator) {
		return catchThrowableOfType(TextGenerationException.class, () -> generator.generate(PROMPT));
	}

	// --- the request on the wire ------------------------------------------------------------

	@Test
	void theKeyTravelsInTheHeaderAndNeverInTheUrl() {
		stub.respondJson(200, ok("Tekst."));

		generator().generate(PROMPT);

		GeminiStub.Recorded request = stub.requests().getFirst();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.rawPath()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:generateContent");
		assertThat(request.rawQuery()).as("no query string at all, so no ?key=").isNull();
		assertThat(request.apiKeyHeader()).isEqualTo(FAKE_KEY);
		assertThat(request.body()).doesNotContain(FAKE_KEY);
	}

	@Test
	void theRequestPinsMinimalThinkingAndTheConfiguredOutputLimit() {
		stub.respondJson(200, ok("Tekst."));

		generator().generate(PROMPT);

		JsonNode body = JSON.readTree(stub.requests().getFirst().body());
		assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(MAX_OUTPUT_TOKENS);
		assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asString())
				.isEqualTo("MINIMAL");
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.isEqualTo(GuidePrompt.SYSTEM_INSTRUCTION);
		assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asString())
				.isEqualTo(GuidePrompt.userText(PROMPT));
	}

	// --- success -------------------------------------------------------------------------------

	@Test
	void theTextIsTheAnswerPartsJoinedWithoutThoughtParts() {
		stub.respondJson(200, response("STOP", List.of(
				Map.of("text", "rozważam", "thought", true),
				Map.of("text", "  Pierwsze zdanie."),
				Map.of("text", " Drugie zdanie.  "))));

		GeneratedText generated = generator().generate(PROMPT);

		assertThat(generated.text()).isEqualTo("Pierwsze zdanie. Drugie zdanie.");
		assertThat(generated.promptVersion()).isEqualTo(GuidePrompt.VERSION);
	}

	@Test
	void aTextOfExactlyTheLimitIsAccepted() {
		stub.respondJson(200, ok("a".repeat(GeminiTextGenerator.MAX_TEXT_LENGTH)));

		assertThat(generator().generate(PROMPT).text()).hasSize(GeminiTextGenerator.MAX_TEXT_LENGTH);
	}

	// --- HTTP status classification ---------------------------------------------------------

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 504 })
	void aServerErrorIsTransient(int status) {
		stub.respondJson(status, error(status, "UNAVAILABLE", "The model is overloaded."));

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.TRANSIENT);
	}

	/** 404 is what a wrong model ID looks like: it must not be retried on every POI. */
	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 409, 422 })
	void aClientErrorOtherThan429IsPermanent(int status) {
		stub.respondJson(status, error(status, "NOT_FOUND", "models/x is not found"));

		TextGenerationException failure = failureOf(generator());

		assertThat(failure.failedClass()).isEqualTo(FailedClass.PERMANENT);
		assertThat(failure.getMessage()).contains("HTTP " + status);
	}

	/** The one 4xx that is a timeout, not a verdict on the request. */
	@Test
	void a408IsTransient() {
		stub.respondJson(408, error(408, "DEADLINE_EXCEEDED", "Request timed out."));

		TextGenerationException failure = failureOf(generator());

		assertThat(failure.failedClass()).isEqualTo(FailedClass.TRANSIENT);
		assertThat(failure.getMessage()).contains("HTTP 408");
	}

	// --- a refused key is not a POI failure ---------------------------------------------------

	@ParameterizedTest
	@ValueSource(ints = { 401, 403 })
	void a401Or403IsARejectedKeyNotAFailedClass(int status) {
		stub.respondJson(status, error(status, "PERMISSION_DENIED", "Method doesn't allow unregistered callers."));

		Throwable failure = catchThrowable(() -> generator().generate(PROMPT));

		assertThat(failure).isExactlyInstanceOf(ProviderCredentialRejectedException.class)
				.hasMessageContaining("API key rejected")
				.hasMessageContaining("HTTP " + status);
	}

	/** What Gemini answers for a key it does not know: a 400 that only the ErrorInfo tells apart. */
	@Test
	void a400WithApiKeyInvalidIsARejectedKey() {
		stub.respondJson(400, invalidArgument(GeminiTextGenerator.API_KEY_INVALID));

		Throwable failure = catchThrowable(() -> generator().generate(PROMPT));

		assertThat(failure).isExactlyInstanceOf(ProviderCredentialRejectedException.class)
				.hasMessageContaining("HTTP 400 INVALID_ARGUMENT");
	}

	@Test
	void a400WithAnotherErrorInfoReasonStaysPermanent() {
		stub.respondJson(400, invalidArgument("FIELD_INVALID"));

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.PERMANENT);
	}

	@Test
	void a429NamingADailyQuotaIsQuota() {
		stub.respondJson(429, quota429("GenerateRequestsPerDayPerProjectPerModel-FreeTier"));

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.QUOTA);
	}

	@Test
	void a429NamingAPerMinuteLimitIsTransient() {
		stub.respondJson(429, quota429("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"));

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.TRANSIENT);
	}

	@Test
	void a429WithTheDocumentedQuotaExceededCodeIsQuota() {
		stub.respondJson(429, "{\"error\":{\"code\":\"quota_exceeded\",\"message\":\"You have exceeded your daily quota.\"}}");

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.QUOTA);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}", "not json", "" })
	void a429ThatDoesNotSayWhichLimitIsTransient(String body) {
		stub.respondJson(429, body);

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.TRANSIENT);
	}

	// --- 200 that is still a failure ---------------------------------------------------------

	@Test
	void aBlockedPromptIsPermanent() {
		stub.respondJson(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");

		TextGenerationException failure = failureOf(generator());

		assertThat(failure.failedClass()).isEqualTo(FailedClass.PERMANENT);
		assertThat(failure.getMessage()).contains("SAFETY");
	}

	/** Safety and the other filters, and MAX_TOKENS: retrying the same prompt repeats the verdict. */
	@ParameterizedTest
	@ValueSource(strings = { "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "LANGUAGE",
			"MAX_TOKENS", "OTHER", "FINISH_REASON_UNSPECIFIED", "" })
	void aCandidateThatDidNotStopNormallyIsPermanentEvenWithText(String finishReason) {
		stub.respondJson(200, response(finishReason, List.of(Map.of("text", "Ucięty tek"))));

		TextGenerationException failure = failureOf(generator());

		assertThat(failure.failedClass()).isEqualTo(FailedClass.PERMANENT);
		assertThat(failure.getMessage()).contains("finishReason=" + finishReason);
	}

	@ParameterizedTest
	@MethodSource("emptyAnswers")
	void anEmptyAnswerIsPermanent(String body) {
		stub.respondJson(200, body);

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.PERMANENT);
	}

	static Stream<String> emptyAnswers() {
		return Stream.of(
				"{}",
				"{\"candidates\":[]}",
				response("STOP", List.of()),
				response("STOP", List.of(Map.of("text", " \n "))),
				response("STOP", List.of(Map.of("text", "tylko myśli", "thought", true))),
				"not json",
				"");
	}

	@Test
	void aTextOverTheLimitIsPermanentNotTruncated() {
		stub.respondJson(200, ok("a".repeat(GeminiTextGenerator.MAX_TEXT_LENGTH + 1)));

		TextGenerationException failure = failureOf(generator());

		assertThat(failure.failedClass()).isEqualTo(FailedClass.PERMANENT);
		assertThat(failure.getMessage()).contains("limit is " + GeminiTextGenerator.MAX_TEXT_LENGTH);
	}

	@Test
	void aBodyOverTheCapIsPermanent() {
		stub.respondJson(200, "\"" + "x".repeat(GeminiTextGenerator.MAX_RESPONSE_BYTES) + "\"");

		assertThat(failureOf(generator()).failedClass()).isEqualTo(FailedClass.PERMANENT);
	}

	// --- no answer ----------------------------------------------------------------------------

	@Test
	void aRefusedConnectionIsTransient() throws IOException {
		assertThat(failureOf(generator(closedPortUrl(), Duration.ofSeconds(2))).failedClass())
				.isEqualTo(FailedClass.TRANSIENT);
	}

	@Test
	@Timeout(10)
	void aStallInTheBodyIsCutByTheReadTimeoutAndIsTransient() {
		stub.stallInBody();

		assertThat(failureOf(generator(stub.baseUrl(), Duration.ofSeconds(1))).failedClass())
				.isEqualTo(FailedClass.TRANSIENT);
	}

	/** Review M4-09: an interrupted blocking call is TRANSIENT, never PERMANENT, and keeps the flag. */
	@Test
	@Timeout(10)
	void anInterruptedCallIsTransientAndTheInterruptIsKept() throws Exception {
		stub.hang();
		AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();

		Throwable thrown = interruptedCall(generator(stub.baseUrl(), Duration.ofSeconds(30)), interruptedAfter);

		assertThat(thrown).isInstanceOf(TextGenerationException.class);
		assertThat(((TextGenerationException) thrown).failedClass()).isEqualTo(FailedClass.TRANSIENT);
		assertThat(interruptedAfter.get()).isTrue();
	}

	/** Calls on a virtual thread, interrupts it once the stub holds the request, returns what it threw. */
	private Throwable interruptedCall(GeminiTextGenerator generator, AtomicReference<Boolean> interruptedAfter)
			throws InterruptedException {
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				generator.generate(PROMPT);
			}
			catch (Throwable ex) {
				thrown.set(ex);
			}
			interruptedAfter.set(Thread.currentThread().isInterrupted());
		});
		assertThat(stub.awaitRequest(5)).isTrue();

		caller.interrupt();
		caller.join(5_000);
		return thrown.get();
	}

	// --- the key never leaks into a failure ------------------------------------------------

	enum Call {
		DIRECT, REFUSED, INTERRUPTED
	}

	/**
	 * Each failure path, with a provider that echoes the key back in its error body: neither the
	 * adapter's exception nor anything in its cause chain, as message, toString or stack trace,
	 * holds the key.
	 *
	 * <p>This cannot catch the key travelling in the URL: Spring drops the query string from the
	 * message of a {@code ResourceAccessException}, so a {@code ?key=} would pass here. That vector is
	 * {@code theKeyTravelsInTheHeaderAndNeverInTheUrl}.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("failurePaths")
	@Timeout(15)
	void noFailureCarriesTheKeyAnywhereInItsCauseChain(String name, Consumer<GeminiStub> scenario, Call call)
			throws Exception {
		scenario.accept(stub);
		Throwable failure = switch (call) {
			case DIRECT -> catchThrowable(() -> generator(stub.baseUrl(), Duration.ofSeconds(1)).generate(PROMPT));
			case REFUSED -> catchThrowable(() -> generator(closedPortUrl(), Duration.ofSeconds(1)).generate(PROMPT));
			case INTERRUPTED -> interruptedCall(generator(stub.baseUrl(), Duration.ofSeconds(30)), new AtomicReference<>());
		};

		assertThat(failure).isInstanceOfAny(TextGenerationException.class, ProviderCredentialRejectedException.class);
		List<String> texts = new ArrayList<>();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			texts.add(String.valueOf(cause.getMessage()));
			texts.add(cause.toString());
		}
		StringWriter trace = new StringWriter();
		failure.printStackTrace(new PrintWriter(trace));
		texts.add(trace.toString());
		assertThat(texts).noneMatch(text -> text.contains(FAKE_KEY));
	}

	static Stream<Arguments> failurePaths() {
		String echo = "{\"error\":{\"code\":%d,\"status\":\"X\",\"message\":\"key " + FAKE_KEY + " rejected\"}}";
		String invalidKeyEcho = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"key " + FAKE_KEY
				+ " not valid\",\"details\":[{\"reason\":\"" + GeminiTextGenerator.API_KEY_INVALID + "\"}]}}";
		return Stream.of(
				Arguments.of("429 echoing the key", (Consumer<GeminiStub>) s -> s.respondJson(429, echo.formatted(429)), Call.DIRECT),
				Arguments.of("500 echoing the key", (Consumer<GeminiStub>) s -> s.respondJson(500, echo.formatted(500)), Call.DIRECT),
				Arguments.of("400 echoing the key", (Consumer<GeminiStub>) s -> s.respondJson(400, echo.formatted(400)), Call.DIRECT),
				Arguments.of("401 echoing the key", (Consumer<GeminiStub>) s -> s.respondJson(401, echo.formatted(401)), Call.DIRECT),
				Arguments.of("403 echoing the key", (Consumer<GeminiStub>) s -> s.respondJson(403, echo.formatted(403)), Call.DIRECT),
				Arguments.of("400 API_KEY_INVALID echoing the key",
						(Consumer<GeminiStub>) s -> s.respondJson(400, invalidKeyEcho), Call.DIRECT),
				Arguments.of("blocked prompt", (Consumer<GeminiStub>) s -> s.respondJson(200,
						"{\"promptFeedback\":{\"blockReason\":\"" + FAKE_KEY + "\"}}"), Call.DIRECT),
				Arguments.of("timeout", (Consumer<GeminiStub>) GeminiStub::hang, Call.DIRECT),
				Arguments.of("interrupted call", (Consumer<GeminiStub>) GeminiStub::hang, Call.INTERRUPTED),
				Arguments.of("connection refused", (Consumer<GeminiStub>) s -> { }, Call.REFUSED));
	}

	// --- fixtures -------------------------------------------------------------------------------

	static String ok(String text) {
		return response("STOP", List.of(Map.of("text", text)));
	}

	static String response(String finishReason, List<? extends Map<String, ?>> parts) {
		return JSON.writeValueAsString(Map.of(
				"candidates", List.of(Map.of(
						"content", Map.of("role", "model", "parts", parts),
						"finishReason", finishReason,
						"index", 0)),
				"usageMetadata", Map.of("promptTokenCount", 120, "candidatesTokenCount", 400,
						"thoughtsTokenCount", 12, "totalTokenCount", 532),
				"modelVersion", MODEL));
	}

	static String error(int status, String grpcStatus, String message) {
		return JSON.writeValueAsString(Map.of("error", Map.of("code", status, "status", grpcStatus, "message", message)));
	}

	static String invalidArgument(String reason) {
		return JSON.writeValueAsString(Map.of("error", Map.of(
				"code", 400,
				"status", "INVALID_ARGUMENT",
				"message", "API key not valid. Please pass a valid API key.",
				"details", List.of(
						Map.of("@type", "type.googleapis.com/google.rpc.ErrorInfo",
								"reason", reason,
								"domain", "googleapis.com",
								"metadata", Map.of("service", "generativelanguage.googleapis.com"))))));
	}

	static String quota429(String quotaId) {
		return JSON.writeValueAsString(Map.of("error", Map.of(
				"code", 429,
				"status", "RESOURCE_EXHAUSTED",
				"message", "You exceeded your current quota.",
				"details", List.of(
						Map.of("@type", "type.googleapis.com/google.rpc.QuotaFailure",
								"violations", List.of(Map.of(
										"quotaMetric", "generativelanguage.googleapis.com/generate_content_free_tier_requests",
										"quotaId", quotaId,
										"quotaDimensions", Map.of("location", "global", "model", MODEL),
										"quotaValue", "500"))),
						Map.of("@type", "type.googleapis.com/google.rpc.RetryInfo", "retryDelay", "43s")))));
	}

	static String closedPortUrl() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return "http://127.0.0.1:" + socket.getLocalPort();
		}
	}
}
