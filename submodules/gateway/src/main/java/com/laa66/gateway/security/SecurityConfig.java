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
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

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

	/**
	 * Tokenless POSTs. {@code /refresh} and {@code /logout} are the two operations authenticated by
	 * <em>possession of the refresh credential itself</em> (httpOnly refresh cookie + the double-submit
	 * {@code csrf} cookie echoed in {@code X-CSRF-Token}, both asserted by the auth service), not by an
	 * access token — so {@code /logout} is the second member of a coherent class here, not a hole in
	 * the matrix (M2-19, route matrix revised 2026-10-03).
	 *
	 * <p>Why {@code /logout} must be tokenless: the boot-time logout replay has to <em>destroy</em> a
	 * session without first <em>creating</em> one. A refresh-then-logout replay would mint a live 15-min
	 * access token on a shared device every time a logout had failed — the exact threat M2-19 closes.
	 *
	 * <p>A bearer that IS present on {@code /logout} is not waved through: the auth service re-verifies
	 * it itself ({@code AccessTokenVerifier} — signature, issuer, non-blank {@code jti}, unexpired, with
	 * no clock skew, i.e. stricter than the gateway here) and 401s a tampered or expired one, so the
	 * cookie-only path cannot be reached by presenting a bad token.
	 */
	private static final String[] PUBLIC_AUTH_POSTS = {
			"/api/auth/register",
			"/api/auth/login",
			"/api/auth/refresh",
			"/api/auth/logout",
			"/api/auth/verify",
			"/api/auth/resend",
			"/api/auth/forgot-password",
			"/api/auth/reset-password"
	};

	private static final String CONTENT_INTERNAL_PREFIX = "/api/content/internal";

	/**
	 * The worker-facing surface of content: {@code /api/content/internal/**}, matched
	 * case-insensitively AND on DECODED path segments.
	 *
	 * <p>Two earlier versions of this rule leaked, and the second one is the lesson:
	 * {@code pathMatchers("/api/content/internal/**")} is case-sensitive, so
	 * {@code /api/content/Internal/results} missed it and was forwarded. Replacing it with a
	 * hand-rolled matcher that folded {@code getRequest().getPath().value()} to lower case fixed the
	 * case variants and opened a worse hole: that value is the RAW path, so
	 * {@code /api/content/%69nternal/results} and even {@code /%61pi/content/%69nternal/results}
	 * were forwarded to content with a trusted {@code X-User-Id} (measured 200). The route predicate
	 * decodes segments before matching; a matcher reading the raw value does not.
	 *
	 * <p>So the fix is a {@link PathPattern} after all — the default parser simply had case
	 * sensitivity on. A pattern parsed by {@link PathPatternParser} with
	 * {@code setCaseSensitive(false)} matches what the route predicate matches, decoded segment by
	 * decoded segment, which is the only comparison that can keep the promise.
	 *
	 * <p>And the boundary stated plainly, because getting it wrong is what produced the second
	 * hole: Spring MVC's case-sensitive request mapping is NOT a second line of defence here. It
	 * only disposes of case variants. Tomcat decodes {@code %69} before mapping, so an encoded
	 * {@code internal} that slipped past the gateway would hit a future
	 * {@code @PostMapping("/internal/results")} exactly, for any logged-in user.
	 *
	 * <p>Scope is deliberate: content's surface only. {@code /api/spatial/internal/...} is forwarded
	 * (measured) because spatial has no such surface. A second service with one adds its own pattern
	 * and an {@code OrServerWebExchangeMatcher}, rather than widening this into "any path containing
	 * /internal/".
	 */
	private static final ServerWebExchangeMatcher CONTENT_INTERNAL =
			caseInsensitive(CONTENT_INTERNAL_PREFIX + "/**");

	/** {@code /**} also matches zero segments, so the bare prefix is covered by the same pattern. */
	private static ServerWebExchangeMatcher caseInsensitive(String pattern) {
		PathPatternParser parser = new PathPatternParser();
		parser.setCaseSensitive(false);
		return new PathPatternParserServerWebExchangeMatcher(parser.parse(pattern));
	}

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
						// Only the enumerated public POSTs are tokenless — NOT all of /api/auth/**
						// (that would expose /api/auth/settings). The relaxation for /logout is
						// exactly one entry.
						.pathMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS).permitAll()
						// Service-to-service surface, never a browser surface: the worker reports
						// generation results to content on /internal/**. A worker is not a user, so
						// no access token can authorise it -- and anyExchange().authenticated()
						// cannot tell the two apart: any logged-in user's valid token would reach
						// /internal/results as a trusted X-User-Id. The rule sits HERE because the
						// authorization matcher sees the path BEFORE StripPrefix=2 rewrites it, and
						// /internal/** stays reachable only from inside the compose network.
						.matchers(CONTENT_INTERNAL).denyAll()
						// Everything else (/api/auth/settings, /api/spatial/**, /api/content/**, and
						// any unmapped path) requires a valid JWT. Absent/invalid/expired token on a
						// protected route -> 401 via the resource-server entry point.
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
