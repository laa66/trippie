package com.laa66.auth.infrastructure.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

/**
 * Loads the ES256 signing keypair from an external EC P-256 JWK file (private material included).
 * Every failure is fatal at startup: the token machinery must not come up with an absent, malformed,
 * public-only, wrong-curve, or unlabelled key.
 */
public final class JwtKeyLoader {

	private JwtKeyLoader() {
	}

	public static ECKey load(String jwkPath) {
		if (jwkPath == null || jwkPath.isBlank()) {
			throw new IllegalStateException("auth.jwt.jwk-path is not configured");
		}
		String json;
		try {
			json = Files.readString(Path.of(jwkPath));
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read signing JWK at " + jwkPath, ex);
		}

		ECKey key;
		try {
			key = ECKey.parse(json);
		}
		catch (ParseException ex) {
			throw new IllegalStateException("malformed EC JWK at " + jwkPath, ex);
		}

		if (!key.isPrivate()) {
			throw new IllegalStateException("signing JWK has no private material: " + jwkPath);
		}
		if (!Curve.P_256.equals(key.getCurve())) {
			throw new IllegalStateException("signing key must be EC P-256, was " + key.getCurve());
		}
		if (key.getKeyID() == null || key.getKeyID().isBlank()) {
			throw new IllegalStateException("signing JWK must carry a kid: " + jwkPath);
		}
		return key;
	}
}
