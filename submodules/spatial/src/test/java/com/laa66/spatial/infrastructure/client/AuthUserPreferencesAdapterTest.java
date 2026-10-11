package com.laa66.spatial.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/**
 * Response handling for the auth settings adapter: what the adapter does with each shape auth can
 * hand back. {@link MockRestServiceServer} substitutes the request factory, so these tests say
 * nothing about the configured timeouts — that half is covered by
 * {@link AuthUserPreferencesTimeoutTest} against a real socket.
 */
class AuthUserPreferencesAdapterTest {

	private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final String BASE_URL = "http://auth:8080";

	private MockRestServiceServer authServer;
	private AuthUserPreferencesAdapter adapter;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.authServer = MockRestServiceServer.bindTo(builder).build();
		this.adapter = new AuthUserPreferencesAdapter(builder.build());
	}

	private ResponseActions expectSettingsCall() {
		return authServer.expect(requestTo(BASE_URL + "/settings"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("X-User-Id", USER.toString()));
	}

	/**
	 * The test that keeps the five fail-closed tests honest: without it, an adapter hardcoded to
	 * {@code return true} would pass every one of them.
	 */
	@Test
	void proximityNotRequired_isHonoured() {
		expectSettingsCall().andRespond(
				withSuccess("{\"requireProximity\":false}", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isFalse();
		authServer.verify();
	}

	@Test
	void proximityRequired_isHonoured() {
		expectSettingsCall().andRespond(
				withSuccess("{\"requireProximity\":true}", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	/**
	 * The full {@code SettingsResponse} auth actually sends. Proves the adapter projects one field
	 * and ignores the rest, so neither the categories nor the content mode leak into spatial and
	 * auth can add fields without breaking this client.
	 */
	@Test
	void realSettingsPayload_isProjectedToTheSingleField() {
		expectSettingsCall().andRespond(withSuccess("""
				{"defaultContentMode":"TEXT","selectedCategories":["sacred","museum"],"requireProximity":false}
				""", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isFalse();
		authServer.verify();
	}

	@Test
	void serverError_failsClosed() {
		expectSettingsCall().andRespond(withServerError());

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	@Test
	void unauthorized_failsClosed() {
		expectSettingsCall().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	@Test
	void emptyBody_failsClosed() {
		expectSettingsCall().andRespond(withSuccess());

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	/**
	 * A 200 whose JSON parses but lacks the field — an auth rolled back past the
	 * {@code require_proximity} migration looks exactly like this. The boxed {@code Boolean}
	 * routes it through the explicit null check rather than leaving the outcome to a Jackson
	 * default; see {@code SettingsPayload}.
	 */
	@Test
	void fieldAbsentFromJson_failsClosed() {
		expectSettingsCall().andRespond(
				withSuccess("{\"defaultContentMode\":\"TEXT\"}", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	/**
	 * A 200 carrying the field explicitly as JSON {@code null}. Distinct from the field being
	 * absent, and the case a primitive component could not represent at all.
	 */
	@Test
	void fieldExplicitlyNull_failsClosed() {
		expectSettingsCall().andRespond(
				withSuccess("{\"requireProximity\":null}", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}

	@Test
	void malformedJson_failsClosed() {
		expectSettingsCall().andRespond(
				withSuccess("{\"requireProximity\": tr", MediaType.APPLICATION_JSON));

		assertThat(adapter.requireProximity(USER)).isTrue();
		authServer.verify();
	}
}
