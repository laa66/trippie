package com.laa66.auth.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token signing configuration ({@code auth.jwt.*}). The keypair is loaded from
 * {@code jwkPath} — an external EC P-256 JWK file provisioned outside the JVM (dev: the gitignored
 * {@code make auth-keys} output; prod: a mounted secret). It is never generated per restart and
 * never committed.
 *
 * <p>{@code clockSkew} is the validation tolerance the gateway applies (frozen 60 s) — a token is
 * honoured until {@code exp + clockSkew}. Logout sizes the {@code jti} denylist entry to
 * {@code remainingLife + clockSkew} so a denied token cannot slip through the gateway's skew window
 * after its denylist entry would otherwise have expired. Kept here as the single source for the value
 * on the auth side, to be matched by the gateway config in M2-11/M2-12.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
		String jwkPath,
		@DefaultValue("https://auth.trippie.local") String issuer,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("60s") Duration clockSkew) {
}
