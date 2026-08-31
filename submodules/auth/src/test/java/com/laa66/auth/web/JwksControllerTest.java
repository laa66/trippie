package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import com.laa66.auth.infrastructure.web.JwksController;

/**
 * Boundary slice for {@code GET /.well-known/jwks.json}: the endpoint serves the public key with the
 * matching {@code kid} and never leaks private material. An ephemeral P-256 key backs the slice.
 *
 * <p>LOW-1 (conscious debt): this asserts on the endpoint's actual response rather than isolating
 * {@code toPublicJWK()} in a pure unit test — acceptable because the response is what leaks or does
 * not, and the no-{@code d} / not-private assertions below prove it directly.
 */
@WebMvcTest(JwksController.class)
@Import(JwksControllerTest.TestKeyConfig.class)
class JwksControllerTest {

	@TestConfiguration
	static class TestKeyConfig {
		@Bean
		ECKey signingKey() throws Exception {
			return new ECKeyGenerator(Curve.P_256).keyID("kid-jwks-1").generate();
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ECKey signingKey;

	@Test
	void jwks_returnsPublicKeyWithMatchingKid() throws Exception {
		mvc.perform(get("/.well-known/jwks.json"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.keys.length()").value(1))
				.andExpect(jsonPath("$.keys[0].kty").value("EC"))
				.andExpect(jsonPath("$.keys[0].crv").value("P-256"))
				.andExpect(jsonPath("$.keys[0].kid").value("kid-jwks-1"))
				.andExpect(jsonPath("$.keys[0].x").exists())
				.andExpect(jsonPath("$.keys[0].y").exists());
	}

	@Test
	void jwks_neverExposesPrivateMaterial() throws Exception {
		MvcResult result = mvc.perform(get("/.well-known/jwks.json"))
				.andExpect(jsonPath("$.keys[0].d").doesNotExist())
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("\"d\"");

		JWKSet served = JWKSet.parse(body);
		ECKey servedKey = served.getKeys().get(0).toECKey();
		assertThat(servedKey.isPrivate()).isFalse();
		// The served public key is the public half of the configured signing key.
		assertThat(servedKey.toJSONObject()).isEqualTo(signingKey.toPublicJWK().toJSONObject());
	}
}
