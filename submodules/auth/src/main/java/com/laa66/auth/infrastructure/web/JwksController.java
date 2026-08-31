package com.laa66.auth.infrastructure.web;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;

/**
 * Serves the public half of the signing key at {@code GET /.well-known/jwks.json} so the gateway can
 * validate access tokens locally (flow 03). Only the public JWK is exposed — {@link ECKey#toPublicJWK()}
 * strips the private {@code d} parameter — so no private material can leak through this route.
 */
@RestController
public class JwksController {

	private final JWKSet publicJwks;

	public JwksController(ECKey signingKey) {
		this.publicJwks = new JWKSet(signingKey.toPublicJWK());
	}

	// LOW-2 (conscious debt): the served JWK omits `use`/`alg` hints. Harmless — the gateway keys off
	// `kid` + verifies with ES256 regardless — to be revisited when M2-11 wires the gateway decoder.
	@GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> jwks() {
		return publicJwks.toJSONObject();
	}
}
