package com.laa66.worker.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.laa66.worker.infrastructure.client.GeminiApiKey;

/**
 * The provider key file, read once at startup. Either the key, or the reason there is none.
 *
 * <p>No reason ever quotes the file's content: a file that holds something other than a key may
 * still hold most of one.
 */
public sealed interface ApiKeyFile {

	/** A key is about 40 characters; a file far larger than that is not a key file. */
	int MAX_FILE_BYTES = 1_024;

	record Loaded(GeminiApiKey key) implements ApiKeyFile {
	}

	record Unavailable(String reason) implements ApiKeyFile {
	}

	static ApiKeyFile read(String location) {
		if (location == null || location.isBlank()) {
			return new Unavailable("GEMINI_API_KEY_FILE is not set");
		}
		Path path = Path.of(location);
		if (Files.isDirectory(path)) {
			return new Unavailable(path + " is a directory, not a key file (a bind mount of a missing file creates one)");
		}
		if (!Files.exists(path)) {
			return new Unavailable(path + " does not exist");
		}
		byte[] bytes;
		try (InputStream in = Files.newInputStream(path)) {
			bytes = in.readNBytes(MAX_FILE_BYTES + 1);
		}
		catch (IOException ex) {
			return new Unavailable(path + " is not readable by the worker (the file mode must be 644)");
		}
		if (bytes.length > MAX_FILE_BYTES) {
			return new Unavailable(path + " is over " + MAX_FILE_BYTES + " bytes, which is not an API key");
		}
		String key = new String(bytes, StandardCharsets.US_ASCII).strip();
		if (key.isEmpty()) {
			return new Unavailable(path + " is empty: put the Gemini API key into it and restart the worker");
		}
		if (!key.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
			return new Unavailable(path + " does not hold a single-line key of printable ASCII characters");
		}
		return new Loaded(new GeminiApiKey(key));
	}
}
