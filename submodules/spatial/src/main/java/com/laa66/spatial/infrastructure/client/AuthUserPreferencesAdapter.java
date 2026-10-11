package com.laa66.spatial.infrastructure.client;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.laa66.spatial.domain.port.out.UserPreferences;

/**
 * HTTP adapter for {@link UserPreferences}, projecting one field out of auth's
 * {@code GET /settings}. This is spatial's first outbound runtime dependency on another service.
 *
 * <p>The URI is the auth service root: the gateway's {@code StripPrefix=2} applies to traffic
 * entering from outside, so from inside the compose network the path is plainly
 * {@code GET /settings}. The subject travels in {@code X-User-Id}, the same trusted header the
 * gateway sets and the same one auth reads for a browser call — auth accepts a user id from
 * nowhere else, so there is no body or path variant of this call to get wrong.
 *
 * <p><strong>Trust boundary, stated out loud.</strong> Nothing authenticates spatial to auth here.
 * Every service shares one compose network, so any of them could already claim an arbitrary
 * {@code X-User-Id} against auth; this adapter is the first code to actually rely on that
 * assumption, which makes "the internal network is trusted" load-bearing rather than incidental.
 * The fix (mTLS or internal service tokens) belongs to the Kubernetes era and is tracked as LATER,
 * not worked around here.
 *
 * <p>No caching: an unlock is a rare write, so there is no load to amortise, and a stale cache would
 * make a toggle whose purpose is manual testing lag behind the user (the per-user short-TTL retreat
 * is recorded in the M3 frozen decisions in {@code .claude/PLAN.md} and changes nothing outside this
 * class).
 */
public class AuthUserPreferencesAdapter implements UserPreferences {

	private static final Logger log = LoggerFactory.getLogger(AuthUserPreferencesAdapter.class);

	private final RestClient restClient;

	public AuthUserPreferencesAdapter(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public boolean requireProximity(UUID userId) {
		// Not a remote failure and not fail-closed material: a missing subject is this service's own
		// bug, and swallowing it would log "failed for user null" and hand back the strict answer,
		// dressing a 500 up as an auth outage.
		Objects.requireNonNull(userId, "userId");
		try {
			SettingsPayload payload = restClient.get()
					.uri("/settings")
					.header("X-User-Id", userId.toString())
					.retrieve()
					.body(SettingsPayload.class);

			if (payload == null || payload.requireProximity() == null) {
				// A 200 with no body, or one whose JSON omits the field or sends it as null, is a
				// contract mismatch and not an answer. Reading any of them as "false" is the one
				// degradation that would make the application looser instead of stricter.
				log.warn("auth GET /settings returned no requireProximity for user {}; requiring proximity", userId);
				return true;
			}
			return payload.requireProximity();
		}
		catch (RestClientException ex) {
			// Fail closed on everything auth can do to us, and on nothing else. A read timeout and a
			// refused connection arrive as ResourceAccessException, every 4xx/5xx as
			// HttpStatusCodeException, and a body that cannot be read as a plain RestClientException
			// wrapping HttpMessageNotReadableException — all subclasses of this one, all meaning the
			// preference is unknown. Only the strict answer is safe there, because the lenient one
			// writes permanent granted_by='EXEMPT' rows that no later policy change can take back.
			// Catching RuntimeException instead would also swallow bugs in this method, and would
			// leave the null branch above covered by nothing: it would still return true, so no test
			// could tell the branch from the catch.
			log.warn("auth GET /settings failed for user {}; requiring proximity", userId, ex);
			return true;
		}
	}

	/**
	 * The one field spatial projects out of {@code SettingsResponse}. {@code ignoreUnknown} is what
	 * makes that a projection rather than a coupling: {@code selectedCategories} and
	 * {@code defaultContentMode} arrive on every real response and are dropped here, and auth may
	 * add further fields without breaking this adapter.
	 *
	 * <p>{@code Boolean} rather than {@code boolean}, so that the strict answer is a property of
	 * this class and not of whichever Jackson is on the classpath. A response that parses but
	 * carries no usable value — {@code {}}, an explicit {@code {"requireProximity":null}}, or an
	 * auth rolled back past the {@code require_proximity} migration — becomes a detectable
	 * {@code null} here and takes the same strict route as a timeout. A primitive would instead
	 * make that outcome depend on a mapper default that has already changed once: Jackson 3 throws
	 * on an absent creator property, which this adapter catches and so would also fail closed
	 * today, whereas Jackson 2 bound it silently to {@code false} — "this user is exempt from
	 * proximity" — and nothing here would have noticed. Measured on Jackson 3.1.0, not assumed.
	 *
	 * <p>One mutation of the field's type is not caught: Jackson coerces a number, so
	 * {@code {"requireProximity":0}} parses as {@code false} — exempt. It is unreachable while auth
	 * serialises a Java {@code boolean}, and strict coercion configured on the shared mapper for the
	 * sake of one field would reach far beyond this adapter, so it is named here rather than fixed.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record SettingsPayload(Boolean requireProximity) {
	}
}
