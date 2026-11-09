package com.laa66.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.laa66.worker.support.RabbitContainers;

class WorkerComposeTest {

	private static final Path COMPOSE = Path.of("../../infra/docker-compose.yml");

	@Test
	void theItBrokerImageIsTheOneComposeRuns() throws IOException {
		assertThat(Files.readString(COMPOSE)).containsPattern("(?m)^\\s+image: " + RabbitContainers.IMAGE + "$");
	}

	/** Internal-only, like content: nothing outside the compose network has a reason to reach it. */
	@Test
	void theWorkerPublishesNoHostPortAndWaitsForAHealthyBroker() throws IOException {
		Matcher service = Pattern.compile("(?ms)^  worker:\\n(.*?)(?=^  \\S)").matcher(Files.readString(COMPOSE));
		assertThat(service.find()).as("worker service in compose").isTrue();

		assertThat(service.group(1))
				.doesNotContainPattern("(?m)^    ports:")
				.containsPattern("(?m)^      rabbitmq:\\n        condition: service_healthy$")
				.contains("http://localhost:8080/health");
	}

	/**
	 * The key arrives as a file on a read-only mount of the gitignored infra/secrets/, never as an
	 * env value: env values show up in {@code docker inspect}.
	 */
	@Test
	void theWorkerReadsTheGeminiKeyFromAReadOnlySecretsMount() throws IOException {
		Matcher service = Pattern.compile("(?ms)^  worker:\\n(.*?)(?=^  \\S)").matcher(Files.readString(COMPOSE));
		assertThat(service.find()).isTrue();

		assertThat(service.group(1))
				.containsPattern("(?m)^      GEMINI_API_KEY_FILE: /secrets/gemini\\.key$")
				.containsPattern("(?m)^    volumes:\\n      - \\./secrets:/secrets:ro$")
				.doesNotContainPattern("GEMINI_API_KEY:");
		assertThat(Files.readString(Path.of("../../.gitignore"))).containsPattern("(?m)^infra/secrets/$");
	}
}
