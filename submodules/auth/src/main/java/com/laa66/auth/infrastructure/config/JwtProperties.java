package com.laa66.auth.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token signing configuration ({@code auth.jwt.*}). The keypair is loaded from
 * {@code jwkPath} — an external EC P-256 JWK file provisioned outside the JVM (dev: the gitignored
 * {@code make auth-keys} output; prod: a mounted secret). It is never generated per restart and
 * never committed.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
		String jwkPath,
		@DefaultValue("https://auth.trippie.local") String issuer,
		@DefaultValue("15m") Duration accessTokenTtl) {
}
