package com.laa66.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.support.EphemeralSigningKeyConfig;

/**
 * Wiring proof for the property the gateway (M2-11) relies on: a token minted by the wired
 * {@link MintAccessToken} verifies against the public key served by the wired JWKS endpoint, and
 * their {@code kid}s match. Both beans consume the single {@code signingKey} bean, so two divergent
 * keys (mismatched kid / failed signature) would turn this red. No DB/Redis is touched — Docker-free.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class JwtRoundTripIntegrationTest {

	@Autowired
	private MintAccessToken mintAccessToken;

	@Autowired
	private MockMvc mvc;

	@Test
	void mintedToken_verifiesAgainstServedJwks_withMatchingKid() throws Exception {
		SignedJWT token = SignedJWT.parse(mintAccessToken.mint(UUID.randomUUID()));

		String jwksBody = mvc.perform(get("/.well-known/jwks.json"))
				.andReturn().getResponse().getContentAsString();
		ECKey servedKey = JWKSet.parse(jwksBody).getKeys().get(0).toECKey();

		assertThat(token.getHeader().getKeyID()).isEqualTo(servedKey.getKeyID());
		assertThat(token.verify(new ECDSAVerifier(servedKey))).isTrue();
	}
}
