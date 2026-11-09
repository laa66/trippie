package com.laa66.worker.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyFileTest {

	private static final String FAKE_KEY = "AIzaFAKE-only-for-tests-0123456789abcdef";

	@TempDir
	Path dir;

	@Test
	void aKeyWithTheTrailingNewlineOfAnEditorIsLoadedStripped() throws IOException {
		ApiKeyFile keyFile = ApiKeyFile.read(write(FAKE_KEY + "\n").toString());

		assertThat(keyFile).isInstanceOf(ApiKeyFile.Loaded.class);
		assertThat(keyFile.toString()).doesNotContain(FAKE_KEY).contains("redacted");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void anUnsetVariableIsNamedAsTheReason(String location) {
		assertThat(reasonOf(ApiKeyFile.read(location))).isEqualTo("GEMINI_API_KEY_FILE is not set");
		assertThat(reasonOf(ApiKeyFile.read(null))).isEqualTo("GEMINI_API_KEY_FILE is not set");
	}

	@Test
	void aMissingFileIsNamed() {
		Path missing = dir.resolve("gemini.key");

		assertThat(reasonOf(ApiKeyFile.read(missing.toString()))).isEqualTo(missing + " does not exist");
	}

	/** What Docker leaves behind when it bind-mounts a file that does not exist. */
	@Test
	void aDirectoryInPlaceOfTheFileIsNamed() throws IOException {
		Path directory = Files.createDirectory(dir.resolve("gemini.key"));

		assertThat(reasonOf(ApiKeyFile.read(directory.toString()))).contains("is a directory");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "\n", "  \n\t \n" })
	void anEmptyFileTellsTheUserWhatToDo(String content) throws IOException {
		assertThat(reasonOf(ApiKeyFile.read(write(content).toString())))
				.contains("is empty").contains("restart the worker");
	}

	@Test
	void anUnreadableFileIsNamedWithTheModeItNeeds() throws IOException {
		Path file = write(FAKE_KEY);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));

		assertThat(reasonOf(ApiKeyFile.read(file.toString()))).contains("not readable").contains("644");
	}

	/** None of these reasons may quote the file: what is not a key can still hold most of one. */
	@ParameterizedTest
	@ValueSource(strings = { FAKE_KEY + " " + FAKE_KEY, FAKE_KEY + "\n" + FAKE_KEY, FAKE_KEY + "\u0000" })
	void contentThatIsNotOneKeyIsRejectedWithoutQuotingIt(String content) throws IOException {
		String reason = reasonOf(ApiKeyFile.read(write(content).toString()));

		assertThat(reason).contains("single-line key").doesNotContain(FAKE_KEY);
	}

	@Test
	void aFileFarLargerThanAKeyIsRejectedWithoutQuotingIt() throws IOException {
		String reason = reasonOf(ApiKeyFile.read(write(FAKE_KEY.repeat(100)).toString()));

		assertThat(reason).contains("not an API key").doesNotContain(FAKE_KEY);
	}

	private Path write(String content) throws IOException {
		return Files.writeString(dir.resolve("gemini.key"), content);
	}

	private static String reasonOf(ApiKeyFile keyFile) {
		assertThat(keyFile).isInstanceOf(ApiKeyFile.Unavailable.class);
		return ((ApiKeyFile.Unavailable) keyFile).reason();
	}
}
