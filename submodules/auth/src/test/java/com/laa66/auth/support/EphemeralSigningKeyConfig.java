package com.laa66.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/**
 * Supplies a per-JVM ephemeral P-256 signing key to full-context tests, standing in for the external
 * dev key that {@code JwtKeyLoader} would otherwise require. It lives in the test source set (never
 * packaged into the jar) and is applied only where explicitly imported, so production can never boot
 * on a generated key — the {@code JwtConfig} loader remains the only runtime path.
 */
@TestConfiguration
public class EphemeralSigningKeyConfig {

	@Bean
	ECKey signingKey() throws Exception {
		return new ECKeyGenerator(Curve.P_256).keyID("test-" + java.util.UUID.randomUUID()).generate();
	}
}
