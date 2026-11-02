package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.laa66.content.support.RabbitContainers;

class GenerationTopologyTest {

	@Test
	void theItBrokerImageIsTheOneComposeRuns() throws IOException {
		assertThat(Files.readString(Path.of("../../infra/docker-compose.yml")))
				.containsPattern("(?m)^\\s+image: " + RabbitContainers.IMAGE + "$");
	}
}
