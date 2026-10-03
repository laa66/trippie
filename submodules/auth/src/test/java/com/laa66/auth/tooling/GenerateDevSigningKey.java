package com.laa66.auth.tooling;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/**
 * Throwaway CLI (not a test — no {@code @Test} methods, never picked up by the test runner):
 * generates the local dev ES256 (EC P-256) signing key as a private JWK JSON, via the exact same
 * {@link ECKeyGenerator} {@code JwtKeyLoaderTest} uses, so the output is guaranteed byte-shape
 * compatible with {@link com.laa66.auth.infrastructure.security.JwtKeyLoader} (kty=EC, crv=P-256,
 * x/y/d, non-blank kid). Lives in test sources on purpose — nimbus is already a main dependency
 * (token minting needs it at runtime), but this generator has no place in the runtime image.
 *
 * <p>Invoked by the root {@code make auth-keys} via the {@code genAuthKey} Gradle task, never at
 * application runtime. Refuses to overwrite an existing output file (atomic {@code CREATE_NEW} —
 * no check-then-write race), so running it directly can never silently clobber a live key.
 */
public final class GenerateDevSigningKey {

	private GenerateDevSigningKey() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1 || args[0].isBlank()) {
			System.err.println("usage: GenerateDevSigningKey <output-path>");
			System.exit(1);
			return;
		}

		Path out = Path.of(args[0]);
		String kid = "dev-" + Long.toHexString(new SecureRandom().nextLong() & 0xFFFFFFFFL);
		ECKey key = new ECKeyGenerator(Curve.P_256).keyID(kid).generate();

		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		try {
			Files.writeString(out, key.toJSONString() + System.lineSeparator(), StandardOpenOption.CREATE_NEW);
		}
		catch (FileAlreadyExistsException e) {
			System.err.println(out + " already exists - refusing to overwrite. Delete it first to regenerate.");
			System.exit(1);
			return;
		}

		System.out.println("Generated dev ES256 signing JWK at " + out + " (kid=" + kid + ")");
	}
}
