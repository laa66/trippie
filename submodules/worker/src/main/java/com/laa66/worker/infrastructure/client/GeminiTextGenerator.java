package com.laa66.worker.infrastructure.client;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.port.out.TextGenerator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * {@link TextGenerator} over the Gemini API's {@code models.generateContent} (REST, v1beta), on a
 * plain {@link RestClient}: no SDK, so no second HTTP client and no second set of transitive
 * versions.
 *
 * <p><strong>The key travels in the {@value #API_KEY_HEADER} header, never in the query
 * string.</strong> URLs end up in exception messages and access logs; a header does not.
 *
 * <p>Classification, all of it here so the rest of the worker sees only a {@link FailedClass} or a
 * rejected credential:
 * <ul>
 * <li>401, 403, and 400 whose {@code ErrorInfo} reason is {@value #API_KEY_INVALID}: the key is
 * refused, a {@link ProviderCredentialRejectedException} with no {@link FailedClass}, since it says
 * nothing about the POI;</li>
 * <li>no answer at all (connect refused, {@link #PROVIDER_TIMEOUT}, an interrupted call), 5xx and
 * 408: {@code TRANSIENT}; 408 is the one 4xx that is a timeout, not a verdict on the request;</li>
 * <li>429: {@code QUOTA} when the body says a daily quota is spent, {@code TRANSIENT} otherwise
 * (a per-minute limit clears within the retry backoff);</li>
 * <li>any other non-2xx: {@code PERMANENT}; a 404 here is most likely a wrong model ID;</li>
 * <li>2xx whose prompt was blocked, whose candidate did not finish with {@code STOP} (safety,
 * recitation, blocklist, {@code MAX_TOKENS} truncation...), or whose text is empty, too long or not
 * JSON: {@code PERMANENT}. Asking again for the same POI gives the same verdict and costs a
 * request.</li>
 * </ul>
 */
public class GeminiTextGenerator implements TextGenerator {

	private static final Logger log = LoggerFactory.getLogger(GeminiTextGenerator.class);

	/**
	 * Wall-clock budget of one call, from connect to the last byte of the body. A constant for the
	 * reason {@code ProviderCallPacer.MIN_PROVIDER_CALL_SPACING} gives, and the number the lease
	 * arithmetic in {@code TaskRetryPolicy} is built on.
	 */
	public static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(20);

	public static final String API_KEY_HEADER = "x-goog-api-key";

	/**
	 * Pinned although it is the model's default today: the thinking tokens count against
	 * {@code maxOutputTokens} and are billed as output, so a changed default would move both the
	 * truncation point and the cost without a line of ours changing.
	 */
	public static final String THINKING_LEVEL = "MINIMAL";

	/**
	 * Longest text accepted, in characters. 250 Polish words are about 1 900; content's own backstop
	 * is 16 384 and is not meant to be met.
	 */
	public static final int MAX_TEXT_LENGTH = 4_000;

	/** A legitimate answer is a few kB; the cap keeps a runaway body out of memory. */
	static final int MAX_RESPONSE_BYTES = 1 << 20;

	private static final int MAX_DETAIL_LENGTH = 300;

	private static final String PATH = "/v1beta/models/{model}:generateContent";

	/** The {@code google.rpc.ErrorInfo} reason Gemini gives, with a 400, for a key it does not know. */
	static final String API_KEY_INVALID = "API_KEY_INVALID";

	private final RestClient restClient;

	private final GeminiApiKey apiKey;

	private final String model;

	private final int maxOutputTokens;

	public GeminiTextGenerator(RestClient restClient, GeminiApiKey apiKey, String model, int maxOutputTokens) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
		this.model = Objects.requireNonNull(model, "model");
		this.maxOutputTokens = maxOutputTokens;
	}

	@Override
	public GeneratedText generate(PoiPrompt prompt) {
		byte[] body = JsonMapper.shared().writeValueAsBytes(requestBody(prompt));
		try {
			return restClient.post()
					.uri(PATH, model)
					.header(API_KEY_HEADER, apiKey.value())
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.exchange((request, response) -> interpret(response.getStatusCode().value(),
							readCapped(response.getBody())), true);
		}
		catch (ResourceAccessException ex) {
			// Timeouts, refused connections and an interrupted blocking call all land here. The
			// interrupt flag is restored by the request factory, so the caller still sees it.
			throw failure(FailedClass.TRANSIENT, "no answer from the provider: " + ex.getMessage(), ex);
		}
	}

	private Map<String, Object> requestBody(PoiPrompt prompt) {
		return Map.of(
				"systemInstruction", Map.of("parts", List.of(Map.of("text", GuidePrompt.SYSTEM_INSTRUCTION))),
				"contents", List.of(Map.of(
						"role", "user",
						"parts", List.of(Map.of("text", GuidePrompt.userText(prompt))))),
				"generationConfig", Map.of(
						"maxOutputTokens", maxOutputTokens,
						"thinkingConfig", Map.of("thinkingLevel", THINKING_LEVEL)));
	}

	private static byte[] readCapped(InputStream body) throws IOException {
		byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
		if (bytes.length > MAX_RESPONSE_BYTES) {
			throw new TextGenerationException(FailedClass.PERMANENT,
					"provider response over " + MAX_RESPONSE_BYTES + " bytes");
		}
		return bytes;
	}

	private GeneratedText interpret(int status, byte[] body) {
		if (status >= 200 && status < 300) {
			return success(parse(body, status));
		}
		JsonNode error = tryParse(body).path("error");
		String detail = "HTTP " + status + " " + error.path("status").asString("") + ": "
				+ error.path("message").asString("");
		if (status == 401 || status == 403 || (status == 400 && isInvalidKey(error))) {
			throw new ProviderCredentialRejectedException(scrubbed("API key rejected, " + detail));
		}
		if (status == 429) {
			throw failure(isDailyQuota(error) ? FailedClass.QUOTA : FailedClass.TRANSIENT, detail, null);
		}
		throw failure(status >= 500 || status == 408 ? FailedClass.TRANSIENT : FailedClass.PERMANENT, detail, null);
	}

	private static boolean isInvalidKey(JsonNode error) {
		for (JsonNode detail : error.path("details")) {
			if (API_KEY_INVALID.equals(detail.path("reason").asString(""))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gemini's 429 carries a {@code google.rpc.QuotaFailure} detail naming the limit that was hit; a
	 * daily one has {@code PerDay} in its quota id (e.g.
	 * {@code GenerateRequestsPerDayPerProjectPerModel-FreeTier}). The API errors page names the same
	 * case {@code quota_exceeded} ("You have exceeded your daily quota"). A 429 with neither is taken
	 * as per-minute: wrongly retrying a spent quota costs two rejected calls, wrongly parking a
	 * per-minute limit costs the POI.
	 */
	private static boolean isDailyQuota(JsonNode error) {
		if ("quota_exceeded".equals(error.path("code").asString(""))) {
			return true;
		}
		for (JsonNode detail : error.path("details")) {
			for (JsonNode violation : detail.path("violations")) {
				String limit = violation.path("quotaId").asString("") + " " + violation.path("quotaMetric").asString("");
				if (limit.toLowerCase(Locale.ROOT).contains("perday")) {
					return true;
				}
			}
		}
		return false;
	}

	private GeneratedText success(JsonNode root) {
		String blockReason = root.path("promptFeedback").path("blockReason").asString("");
		if (!blockReason.isEmpty()) {
			throw failure(FailedClass.PERMANENT, "prompt blocked by the provider: " + blockReason, null);
		}
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			throw failure(FailedClass.PERMANENT, "provider returned no candidate", null);
		}
		String finishReason = candidate.path("finishReason").asString("");
		if (!"STOP".equals(finishReason)) {
			throw failure(FailedClass.PERMANENT, "candidate did not finish: finishReason=" + finishReason, null);
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode part : candidate.path("content").path("parts")) {
			if (!part.path("thought").asBoolean(false)) {
				text.append(part.path("text").asString(""));
			}
		}
		String result = text.toString().strip();
		if (result.isEmpty()) {
			throw failure(FailedClass.PERMANENT, "provider returned an empty text", null);
		}
		if (result.length() > MAX_TEXT_LENGTH) {
			throw failure(FailedClass.PERMANENT,
					"text is " + result.length() + " characters, the limit is " + MAX_TEXT_LENGTH, null);
		}
		JsonNode usage = root.path("usageMetadata");
		log.info("Gemini text generated: model={} words={} chars={} promptTokens={} candidatesTokens={} "
				+ "thoughtsTokens={} totalTokens={}",
				root.path("modelVersion").asString(model), result.split("\\s+").length, result.length(),
				usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0),
				usage.path("thoughtsTokenCount").asInt(0), usage.path("totalTokenCount").asInt(0));
		return new GeneratedText(result, GuidePrompt.VERSION);
	}

	private JsonNode parse(byte[] body, int status) {
		try {
			return JsonMapper.shared().readTree(body);
		}
		catch (JacksonException ex) {
			throw failure(FailedClass.PERMANENT, "HTTP " + status + " with a body that is not JSON", null);
		}
	}

	private static JsonNode tryParse(byte[] body) {
		try {
			return JsonMapper.shared().readTree(body);
		}
		catch (JacksonException ex) {
			return MissingNode.getInstance();
		}
	}

	private TextGenerationException failure(FailedClass failedClass, String detail, Throwable cause) {
		return new TextGenerationException(failedClass, scrubbed(detail), cause);
	}

	/** Every detail is cut and scrubbed of the key, since part of it comes from the provider's body. */
	private String scrubbed(String detail) {
		String scrubbed = apiKey.redact(detail);
		return "gemini: " + (scrubbed.length() > MAX_DETAIL_LENGTH ? scrubbed.substring(0, MAX_DETAIL_LENGTH) : scrubbed);
	}
}
