package com.laa66.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.laa66.worker.domain.model.ContentMode;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PoiContentKey;
import com.laa66.worker.infrastructure.messaging.GenerationTopology;

/**
 * Content and the worker share no library, so the message shape and the broker names exist twice.
 * Nothing at runtime would notice them drifting: a renamed queue leaves both services healthy and
 * the worker idle, a renamed field turns every task into a dead letter. This reads content's
 * sources, the way content's {@code GenerationTopologyTest} reads the compose file, and fails on
 * any difference.
 */
class ContentContractTest {

	private static final Path CONTENT = Path.of("../content/src/main/java/com/laa66/content");

	@Test
	void theBrokerNamesAreContents() throws Exception {
		Map<String, String> contents = stringConstants(read("infrastructure/messaging/GenerationTopology.java"));

		assertThat(contents).isNotEmpty().isEqualTo(stringConstants(GenerationTopology.class));
	}

	@Test
	void theQueueArgumentsAreContents() throws IOException {
		String expected = ".withArguments(Map.of(\"x-dead-letter-exchange\", GenerationTopology.DEAD_LETTER_EXCHANGE))";

		assertThat(read("infrastructure/config/MessagingConfig.java")).contains(expected);
		assertThat(Files.readString(Path.of(
				"src/main/java/com/laa66/worker/infrastructure/config/MessagingConfig.java"))).contains(expected);
	}

	@Test
	void theTaskHasContentsFields() throws IOException {
		assertThat(recordHeader(read("domain/model/GenerateContentTask.java"), "GenerateContentTask"))
				.isEqualTo(components(GenerateContentTask.class));
		assertThat(recordHeader(read("domain/model/PoiContentKey.java"), "PoiContentKey"))
				.isEqualTo(components(PoiContentKey.class));
	}

	@Test
	void theModesAreContents() throws IOException {
		Matcher body = Pattern.compile("enum ContentMode \\{\\s*([^}]*?)\\s*}").matcher(read("domain/model/ContentMode.java"));
		assertThat(body.find()).isTrue();

		assertThat(Arrays.stream(body.group(1).split(",")).map(String::trim).toList())
				.isEqualTo(Arrays.stream(ContentMode.values()).map(Enum::name).toList());
	}

	private static String read(String relative) throws IOException {
		return Files.readString(CONTENT.resolve(relative));
	}

	private static Map<String, String> stringConstants(String source) {
		Matcher m = Pattern.compile("public static final String (\\w+) = \"([^\"]*)\";").matcher(source);
		Map<String, String> constants = new LinkedHashMap<>();
		while (m.find()) {
			constants.put(m.group(1), m.group(2));
		}
		return constants;
	}

	private static Map<String, String> stringConstants(Class<?> type) throws IllegalAccessException {
		Map<String, String> constants = new LinkedHashMap<>();
		for (Field field : type.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				constants.put(field.getName(), (String) field.get(null));
			}
		}
		return constants;
	}

	private static String recordHeader(String source, String name) {
		Matcher m = Pattern.compile("record " + name + "\\(([^)]*)\\)").matcher(source);
		assertThat(m.find()).as("record " + name + " in content").isTrue();
		return Arrays.stream(m.group(1).split(",")).map(String::trim).collect(Collectors.joining(", "));
	}

	private static String components(Class<? extends Record> type) {
		return Arrays.stream(type.getRecordComponents())
				.map(c -> c.getType().getSimpleName() + " " + c.getName())
				.collect(Collectors.joining(", "));
	}
}
