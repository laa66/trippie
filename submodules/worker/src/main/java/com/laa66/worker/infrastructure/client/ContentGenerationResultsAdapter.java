package com.laa66.worker.infrastructure.client;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.PoiContentKey;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.domain.port.out.GenerationResults;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link GenerationResults} over content's {@code GET /internal/lease} and
 * {@code POST /internal/results}.
 *
 * <p>The lease token leaves as {@link Instant#toString()} in the query and as Jackson's ISO-8601
 * string in the body; both keep every digit, which content's equality fence needs (M4-03a). The body
 * is written with {@link JsonMapper#shared()}, as the task was read: {@code spring.jackson.*} must
 * not reshape a contract another service reads.
 *
 * <p>404 and 405 are answers content never gives on these two routes: the worker is pointed at
 * something else, and every task would fail the same way, so the consumer must stop (review M4-11,
 * LOW-3). Any other 4xx is content refusing the request itself: permanent. No answer, a 5xx, or a
 * lease answer without a boolean: transient. The messages never carry the URL, since they become
 * {@code failed_detail} or the {@code /health} reason.
 */
public class ContentGenerationResultsAdapter implements GenerationResults {

	private static final Logger log = LoggerFactory.getLogger(ContentGenerationResultsAdapter.class);

	private final RestClient restClient;

	public ContentGenerationResultsAdapter(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public boolean isLeaseLive(GenerateContentTask task) {
		PoiContentKey key = task.key();
		LeasePayload payload = call("lease check", () -> restClient.get()
				.uri(builder -> builder.path("/internal/lease")
						.queryParam("poiId", "{poiId}")
						.queryParam("mode", key.mode().name())
						.queryParam("lang", "{lang}")
						.queryParam("leasedAt", "{leasedAt}")
						.build(key.osmId(), key.lang(), task.leasedAt().toString()))
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw refused("lease check", response.getStatusCode());
				})
				.body(LeasePayload.class));
		if (payload == null || !(payload.live() instanceof Boolean live)) {
			throw new TransientTaskException("content's lease check returned no boolean");
		}
		return live;
	}

	@Override
	public void complete(GenerateContentTask task, GeneratedText text) {
		post("completion", new ResultPayload(task.key(), task.leasedAt(), "COMPLETED", text.text(),
				text.promptVersion(), null, null));
	}

	@Override
	public void fail(GenerateContentTask task, FailedClass failedClass, String detail) {
		post("failure", new ResultPayload(task.key(), task.leasedAt(), "FAILED", null, null, failedClass, detail));
	}

	private void post(String what, ResultPayload payload) {
		byte[] body = JsonMapper.shared().writeValueAsBytes(payload);
		call(what, () -> restClient.post()
				.uri("/internal/results")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw refused(what, response.getStatusCode());
				})
				.toBodilessEntity());
	}

	private static RuntimeException refused(String what, HttpStatusCode status) {
		String message = "content answered HTTP " + status.value() + " to the " + what;
		if (status.value() == HttpStatus.NOT_FOUND.value() || status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
			return new ConsumerMustStopException(message, "point CONTENT_URI at content and restart the worker");
		}
		return status.is4xxClientError() ? new PermanentTaskException(message) : new TransientTaskException(message);
	}

	private static <T> T call(String what, java.util.function.Supplier<T> exchange) {
		try {
			return exchange.get();
		}
		catch (RestClientException | InvalidMediaTypeException ex) {
			log.warn("content {} failed", what, ex);
			throw new TransientTaskException("content gave no answer to the " + what);
		}
	}

	/** {@code Object}: Jackson would coerce {@code 0} or {@code "false"} into a {@link Boolean}. */
	record LeasePayload(Object live) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ResultPayload(PoiContentKey key, Instant leasedAt, String status, String text, String promptVersion,
			FailedClass failedClass, String failedDetail) {
	}
}
