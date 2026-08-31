package com.laa66.auth.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.nimbusds.jose.jwk.ECKey;

import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.infrastructure.security.JwtKeyLoader;
import com.laa66.auth.infrastructure.security.NimbusAccessTokenMinter;

/** Wires the ES256 signing key, the access-token minter, and exposes the key for the JWKS endpoint. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

	/**
	 * Loads the external keypair, failing fast if it is absent or malformed. This is the only path a
	 * running service takes — there is deliberately no in-JVM key generation here (tests supply an
	 * ephemeral key from the test source set), so no ephemeral-key code is packaged into the jar.
	 * Guarded to {@code !test} so tests supply the key without a bean-definition clash; a real
	 * instance run with the {@code test} profile has no key bean at all (no ephemeral code on the
	 * jar's classpath) and simply fails to start rather than signing with a generated key.
	 */
	@Bean
	@Profile("!test")
	ECKey signingKey(JwtProperties properties) {
		return JwtKeyLoader.load(properties.jwkPath());
	}

	@Bean
	MintAccessToken mintAccessToken(ECKey signingKey, JwtProperties properties) {
		return new NimbusAccessTokenMinter(signingKey, properties.issuer(), properties.accessTokenTtl(),
				Clock.systemUTC());
	}
}
