package com.laa66.content.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.out.PoiUnlocks;

/**
 * HTTP adapter for {@link PoiUnlocks} over spatial's {@code GET /locations/{osmType}/{osmRef}/unlocked}.
 *
 * <p>The URI is the spatial service root: the gateway's {@code StripPrefix=2} applies only to
 * traffic entering from outside. The subject travels in {@code X-User-Id}, built from a {@link UUID},
 * so a crafted header value has no way out of this service. As with spatial's call to auth, nothing
 * authenticates this service to spatial: the trusted compose network is load-bearing (LATER: mTLS).
 *
 * <p><strong>{@code osm_id} carries a slash, so it is sent as two path segments.</strong> Encoding it
 * as one ({@code node%2F42}) is rejected by Tomcat, and raw concatenation would let request input
 * reshape the URL. The id comes from the request (M4-06, M4-08), so an id that is not two URL-safe
 * segments is never sent: it cannot name a POI, and an unknown POI is a definitive {@code false}
 * here just as it is from spatial (M4-08 decides it ends in a 403). The pattern only guards the URL,
 * dots included, since {@code ..} would pass segment encoding untouched; which ids exist is
 * spatial's question.
 *
 * <p><strong>Spatial never answers 404 for an unknown POI</strong> (it is {@code {"unlocked":false}}
 * with a 200), so a 404 on this route means a broken deployment and is unavailability, not "no".
 * The endpoint answers only 200: {@code retrieve()} treats just 4xx/5xx as errors, so without the
 * explicit status check a redirect or a 201 carrying a body would be read as an answer, and M4-05
 * would remember a positive one for ten minutes.
 */
public class SpatialPoiUnlocksAdapter implements PoiUnlocks {

	private static final Logger log = LoggerFactory.getLogger(SpatialPoiUnlocksAdapter.class);

	private static final int HTTP_OK = 200;

	private static final Pattern OSM_ID = Pattern.compile("([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)");

	private final RestClient restClient;

	public SpatialPoiUnlocksAdapter(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public boolean isUnlocked(UUID userId, String osmId) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(osmId, "osmId");

		Matcher segments = OSM_ID.matcher(osmId);
		if (!segments.matches()) {
			return false;
		}

		UnlockStatePayload payload;
		try {
			payload = restClient.get()
					.uri("/locations/{osmType}/{osmRef}/unlocked", segments.group(1), segments.group(2))
					.header("X-User-Id", userId.toString())
					.retrieve()
					.onStatus(status -> status.value() != HTTP_OK, (request, response) -> {
						throw new RestClientException("spatial answered " + response.getStatusCode());
					})
					.body(UnlockStatePayload.class);
		}
		catch (RestClientException | InvalidMediaTypeException ex) {
			// Timeout, refused connection, any status but 200 and an unreadable body arrive as
			// RestClientException. An unparsable Content-Type does not: it is an
			// IllegalArgumentException, thrown whatever the status. Catching RuntimeException instead
			// would dress this method's own bugs up as an outage.
			log.warn("spatial unlock read failed for user {} on {}", userId, osmId, ex);
			throw new PoiUnlockStateUnavailableException("spatial unlock read failed", ex);
		}

		if (payload != null && payload.unlocked() instanceof Boolean unlocked) {
			return unlocked;
		}
		log.warn("spatial unlock read returned no boolean for user {} on {}", userId, osmId);
		throw new PoiUnlockStateUnavailableException("spatial unlock read returned no boolean");
	}

	/**
	 * {@code Object}, not {@code Boolean}: Jackson coerces {@code 0} and {@code "false"} into
	 * {@code Boolean.FALSE}, which would turn a contract break into a "not unlocked". Only a real
	 * JSON boolean binds to a {@link Boolean} here.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record UnlockStatePayload(Object unlocked) {
	}
}
