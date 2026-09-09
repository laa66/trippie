package com.laa66.gateway.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.util.StringUtils;

/**
 * Reactive OAuth2 resource-server security for the gateway (flow 03). Access tokens are validated
 * locally against the auth service's JWKS — no round-trip per request. The authorization matcher
 * runs on the ORIGINAL request path (StripPrefix is a gateway route filter applied later, inside the
 * routing handler), so the matrix below sees {@code /api/auth/**} and {@code /api/spatial/**}.
 *
 * <p>Boot 4 reactive-security wiring: {@code spring-boot-starter-oauth2-resource-server} pulls
 * spring-security-config + oauth2-jose (nimbus-jose-jwt). Because the app is WebFlux, the reactive
 * stack is what activates — this config defines a {@link SecurityWebFilterChain} on
 * {@link ServerHttpSecurity} and a {@link ReactiveJwtDecoder} (the {@code Server*}/reactive beans),
 * NOT the servlet {@code HttpSecurity}/{@code JwtDecoder} variants. Boot's
 * {@code ReactiveOAuth2ResourceServerAutoConfiguration} backs off in favour of the explicit beans
 * here (both are {@code @ConditionalOnMissingBean}).
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

	private static final String[] PUBLIC_AUTH_POSTS = {
			"/api/auth/register",
			"/api/auth/login",
			"/api/auth/refresh",
			"/api/auth/verify",
			"/api/auth/resend",
			"/api/auth/forgot-password",
			"/api/auth/reset-password"
	};

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
			UserIdHeaderFilter userIdHeaderFilter) {
		return http
				// Stateless bearer-token auth: no session, no server-side CSRF (the auth service runs
				// its own double-submit CSRF on /refresh + /logout). No form login / basic auth.
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.authorizeExchange(exchange -> exchange
						.pathMatchers(HttpMethod.GET, "/health", "/actuator/health").permitAll()
						// Only the specific public POSTs are tokenless — NOT all of /api/auth/**
						// (that would expose /api/auth/logout and /api/auth/settings).
						.pathMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS).permitAll()
						// Everything else (/api/auth/logout, /api/auth/settings, /api/spatial/**,
						// and any unmapped path) requires a valid JWT. Absent/invalid/expired token
						// on a protected route -> 401 via the resource-server entry point.
						.anyExchange().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
				// Runs after authentication so ReactiveSecurityContextHolder holds the JWT, before
				// the routing handler forwards the request.
				.addFilterAfter(userIdHeaderFilter, SecurityWebFiltersOrder.AUTHORIZATION)
				.build();
	}

	/**
	 * Decoder over the auth JWKS. ES256 is forced because the served JWK omits {@code alg}
	 * (auth-side LOW-2 debt) — the key is selected by {@code kid} + EC type and verified as ES256.
	 * Validators: timestamp with the frozen 60 s clock skew (a token is honoured until
	 * {@code exp + 60s}) AND issuer pinned to auth's {@code auth.jwt.issuer}.
	 *
	 * <p>The Nimbus decoder is wrapped by {@link DenylistCheckingJwtDecoder} (M2-12): after
	 * cryptographic validation it runs a non-blocking {@code EXISTS auth:denylist:{jti}} and rejects
	 * revoked tokens (fail-closed on Redis errors). The {@code jti} denylist is a reactive Redis call,
	 * so it lives in this decorating decoder rather than the synchronous {@link OAuth2TokenValidator}
	 * chain below.
	 */
	@Bean
	ReactiveJwtDecoder jwtDecoder(
			@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
			@Value("${auth.jwt.issuer}") String issuer,
			ReactiveStringRedisTemplate denylistRedis) {
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
				.jwsAlgorithm(SignatureAlgorithm.ES256)
				.build();
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
				new JwtTimestampValidator(Duration.ofSeconds(60)),
				new JwtIssuerValidator(issuer),
				// A structurally valid token with a blank/absent `sub` carries no identity — reject
				// it at authentication (401) rather than admitting an identity-less request onto a
				// protected route.
				new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
				// A token with no `jti` cannot be denylisted (getId() -> null -> lookup on the literal
				// key auth:denylist:null always misses -> unrevocable). Reject it here (401) so the
				// denylist gate's fail-closed philosophy holds; symmetric with the `sub` gate above.
				new JwtClaimValidator<String>(JwtClaimNames.JTI, StringUtils::hasText));
		decoder.setJwtValidator(validator);
		return new DenylistCheckingJwtDecoder(decoder, denylistRedis);
	}
}
