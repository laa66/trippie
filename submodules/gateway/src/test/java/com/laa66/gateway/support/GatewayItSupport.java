package com.laa66.gateway.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Shared in-JVM stubs + ES256 token minting for the gateway denylist ITs — mirrors auth's
 * {@code NimbusAccessTokenMinter} and {@code JwksController} so the gateway sees production-shaped
 * tokens and a JWKS without {@code use}/{@code alg} hints.
 */
public final class GatewayItSupport {

	private GatewayItSupport() {
	}

	/** Backend stub that echoes the forwarded path and the {@code X-User-Id} it received. */
	public static HttpServer startEchoBackend() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
			respond(exchange, "{\"path\":\"" + path + "\",\"userId\":\""
					+ (userId == null ? "" : userId) + "\"}");
		});
		server.start();
		return server;
	}

	public static HttpServer startJwksServer(ECKey signingKey) throws IOException {
		JWKSet publicJwks = new JWKSet(signingKey.toPublicJWK());
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/.well-known/jwks.json",
				exchange -> respond(exchange, publicJwks.toString()));
		server.start();
		return server;
	}

	public static String baseUri(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public static String jwksUri(HttpServer jwksServer) {
		return baseUri(jwksServer) + "/.well-known/jwks.json";
	}

	public static String mint(ECKey key, String sub, String issuer, String jti, Instant iat, Instant exp)
			throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(sub)
				.issuer(issuer)
				.jwtID(jti)
				.issueTime(Date.from(iat))
				.expirationTime(Date.from(exp))
				.build();
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
				.keyID(key.getKeyID())
				.type(JOSEObjectType.JWT)
				.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		jwt.sign(new ECDSASigner(key.toECPrivateKey()));
		return jwt.serialize();
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
