package com.laa66.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import com.laa66.auth.infrastructure.security.JwtKeyLoader;

/**
 * Proves the signing key loads from an external file and that every malformed input is fatal at
 * load time (fail-fast), so the service can never start with an unusable key.
 */
class JwtKeyLoaderTest {

	@TempDir
	Path tmp;

	private Path write(String name, String content) throws Exception {
		Path file = tmp.resolve(name);
		Files.writeString(file, content);
		return file;
	}

	@Test
	void load_readsPrivateP256JwkFromExternalPath() throws Exception {
		ECKey key = new ECKeyGenerator(Curve.P_256).keyID("kid-load-1").generate();
		Path file = write("key.json", key.toJSONString());

		ECKey loaded = JwtKeyLoader.load(file.toString());

		assertThat(loaded.getKeyID()).isEqualTo("kid-load-1");
		assertThat(loaded.getCurve()).isEqualTo(Curve.P_256);
		assertThat(loaded.isPrivate()).isTrue();
	}

	@Test
	void load_blankPath_fails() {
		assertThatThrownBy(() -> JwtKeyLoader.load("  "))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void load_nullPath_fails() {
		assertThatThrownBy(() -> JwtKeyLoader.load(null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void load_missingFile_fails() {
		assertThatThrownBy(() -> JwtKeyLoader.load(tmp.resolve("absent.json").toString()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void load_malformedJson_fails() throws Exception {
		Path file = write("bad.json", "not-a-jwk");

		assertThatThrownBy(() -> JwtKeyLoader.load(file.toString()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void load_publicOnlyJwk_fails() throws Exception {
		ECKey pub = new ECKeyGenerator(Curve.P_256).keyID("kid").generate().toPublicJWK();
		Path file = write("pub.json", pub.toJSONString());

		assertThatThrownBy(() -> JwtKeyLoader.load(file.toString()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("private");
	}

	@Test
	void load_wrongCurve_fails() throws Exception {
		ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("kid").generate();
		Path file = write("p384.json", p384.toJSONString());

		assertThatThrownBy(() -> JwtKeyLoader.load(file.toString()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("P-256");
	}

	@Test
	void load_missingKid_fails() throws Exception {
		ECKey noKid = new ECKeyGenerator(Curve.P_256).generate();
		Path file = write("nokid.json", noKid.toJSONString());

		assertThatThrownBy(() -> JwtKeyLoader.load(file.toString()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("kid");
	}
}
