package com.laa66.gateway.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Establishes the {@code X-User-Id} trust boundary on every forwarded request (flow 03). Downstream
 * services trust this header ONLY from the gateway, so it is:
 * <ol>
 *   <li>always stripped if the client supplied it (defense-in-depth — no spoofing), then</li>
 *   <li>set to the validated token {@code sub} when the request carries a valid JWT.</li>
 * </ol>
 * Public/tokenless requests are forwarded with no {@code X-User-Id} at all.
 *
 * <p>Registered via {@code addFilterAfter(..., AUTHORIZATION)} so it runs after the authentication
 * filter has populated {@link ReactiveSecurityContextHolder} and before the gateway routing handler
 * forwards the (mutated) request. Fully non-blocking — no work leaves the Netty event loop.
 */
@Component
class UserIdHeaderFilter implements WebFilter {

	static final String USER_ID_HEADER = "X-User-Id";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest stripped = exchange.getRequest().mutate()
				.headers(headers -> headers.remove(USER_ID_HEADER))
				.build();
		ServerWebExchange strippedExchange = exchange.mutate().request(stripped).build();

		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.filter(Authentication::isAuthenticated)
				.map(Authentication::getPrincipal)
				.filter(Jwt.class::isInstance)
				.map(principal -> ((Jwt) principal).getSubject())
				// A valid signature with a blank/absent `sub` yields no trusted identity: forward
				// with NO X-User-Id rather than propagating a null downstream (fail-closed; also
				// avoids a Netty-path NPE in the map below).
				.filter(StringUtils::hasText)
				.map(sub -> strippedExchange.mutate()
						.request(builder -> builder.header(USER_ID_HEADER, sub))
						.build())
				.defaultIfEmpty(strippedExchange)
				.flatMap(chain::filter);
	}
}
