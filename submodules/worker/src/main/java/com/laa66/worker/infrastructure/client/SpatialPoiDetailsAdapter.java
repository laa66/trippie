package com.laa66.worker.infrastructure.client;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.PoiDetail;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.domain.port.out.PoiDetails;

/**
 * {@link PoiDetails} over spatial's user-free {@code GET /locations/{osmType}/{osmRef}} (M4-01).
 *
 * <p>{@code osm_id} carries a slash, so it goes out as two path segments, each through the same
 * two-safe-segments pattern content uses: an id that does not fit cannot name a POI and is unknown,
 * without a call. {@code %2F} would be rejected by spatial's Tomcat, and raw concatenation would let
 * a message reshape the URL.
 *
 * <p>"No such POI" is a 404 carrying spatial's {@code application/problem+json} body with its
 * constant detail {@value #UNKNOWN_POI_DETAIL} ({@code SpatialRequestExceptionHandler}). Spatial
 * answers an unknown route with a problem 404 too, only with another detail, so the media type alone
 * would take a wrong path prefix in {@code SPATIAL_URI} for an unknown POI (measured). Any other 404,
 * and a 405, means the worker is pointed at something that is not spatial's detail endpoint: every
 * task would fail the same way, so the consumer must stop (review M4-11, LOW-3, user decision
 * 2026-11-12). Every other non-200, a timeout and an unreadable body are spatial not
 * answering, which may pass. The exception messages here name the POI and the status, never the
 * URL: they become {@code failed_detail} or the {@code /health} reason.
 */
public class SpatialPoiDetailsAdapter implements PoiDetails {

	private static final Logger log = LoggerFactory.getLogger(SpatialPoiDetailsAdapter.class);

	static final String UNKNOWN_POI_DETAIL = "No POI with that id";

	private static final Pattern OSM_ID = Pattern.compile("([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)");

	private final RestClient restClient;

	public SpatialPoiDetailsAdapter(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public Optional<PoiDetail> find(String osmId) {
		Matcher segments = OSM_ID.matcher(osmId);
		if (!segments.matches()) {
			return Optional.empty();
		}
		try {
			return restClient.get()
					.uri("/locations/{osmType}/{osmRef}", segments.group(1), segments.group(2))
					.exchange((request, response) -> {
						int status = response.getStatusCode().value();
						if (status == HttpStatus.NOT_FOUND.value() && isProblem(response.getHeaders().getContentType())
								&& UNKNOWN_POI_DETAIL.equals(problemDetail(response))) {
							return Optional.<PoiDetail>empty();
						}
						if (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
							throw new ConsumerMustStopException("spatial answered HTTP " + status + " to the detail of " + osmId
									+ ", not spatial's answer to an unknown POI", "point SPATIAL_URI at spatial and restart the worker");
						}
						if (status != HttpStatus.OK.value()) {
							throw new TransientTaskException("spatial answered HTTP " + status + " to the detail of " + osmId);
						}
						DetailPayload payload = response.bodyTo(DetailPayload.class);
						if (payload == null) {
							throw new TransientTaskException("spatial sent an empty detail of " + osmId);
						}
						return Optional.of(new PoiDetail(payload.name(), payload.category()));
					});
		}
		catch (RestClientException | InvalidMediaTypeException ex) {
			log.warn("spatial detail read failed for {}", osmId, ex);
			throw new TransientTaskException("spatial gave no detail of " + osmId);
		}
	}

	/** An empty or unreadable problem body is not spatial's either: {@code null}, so the consumer stops. */
	private static String problemDetail(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			ProblemPayload problem = response.bodyTo(ProblemPayload.class);
			return problem == null ? null : problem.detail();
		}
		catch (RestClientException ex) {
			return null;
		}
	}

	private static boolean isProblem(MediaType contentType) {
		return contentType != null && MediaType.APPLICATION_PROBLEM_JSON.equalsTypeAndSubtype(contentType);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ProblemPayload(String detail) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record DetailPayload(String name, String category) {
	}
}
