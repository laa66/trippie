package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.port.out.ContentRepository;
import com.laa66.content.infrastructure.messaging.GenerationTopology;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.PublishBudget;
import com.laa66.content.support.RabbitContainers;
import com.laa66.content.support.SpatialStub;

import tools.jackson.databind.json.JsonMapper;

/**
 * A broker in a memory alarm takes the bytes and confirms nothing, so a POST waits out the confirm
 * on its request thread and then has to answer. The task it gave up on is not gone: it arrives when
 * the alarm clears, and the lease it names must be dead by then.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentGenerationUnderAlarmIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final SpatialStub SPATIAL = SpatialStub.started();

	/** The gate, the lease and the release around the publish, on a loaded machine. */
	private static final Duration REQUEST_OVERHEAD = Duration.ofSeconds(1);

	@DynamicPropertySource
	static void dependencies(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
		SPATIAL.register(registry);
	}

	@AfterAll
	static void stopSpatial() {
		SPATIAL.stop();
	}

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private ContentRepository repository;

	@Test
	void aPostUnderAnAlarmAnswersWithinThePublishBudgetAndTheLateTaskFindsItsLeaseDead() throws Exception {
		watermark("0");
		HttpResponse<String> response;
		try {
			response = assertTimeoutPreemptively(PublishBudget.PER_PUBLISH.plus(REQUEST_OVERHEAD), this::post);
		}
		finally {
			watermark("0.4");
		}

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("Content generation is temporarily unavailable");
		assertThat(row()).isEqualTo("FAILED TRANSIENT 0 true");

		Message late = rabbit.receive(GenerationTopology.QUEUE, 10_000);
		assertThat(late).as("the task given up on did not arrive after the alarm").isNotNull();
		GenerateContentTask task = JsonMapper.shared().readValue(
				new String(late.getBody(), StandardCharsets.UTF_8), GenerateContentTask.class);

		repository.failGeneration(task.key(), task.leasedAt(), FailedClass.PERMANENT, "late worker");

		assertThat(row()).as("the late task's token still fenced the row").isEqualTo("FAILED TRANSIENT 0 true");
	}

	private String row() {
		return jdbc.sql("SELECT status || ' ' || failed_class || ' ' || attempts || ' ' || (started_at IS NULL)"
				+ " FROM poi_content").query(String.class).single();
	}

	private HttpResponse<String> post() throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/content/generate"))
				.header("Content-Type", "application/json")
				.header("X-User-Id", UUID.randomUUID().toString())
				.POST(HttpRequest.BodyPublishers.ofString("{\"poiId\":\"node/open1\",\"mode\":\"TEXT\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static void watermark(String fraction) throws Exception {
		assertThat(BROKER.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", fraction).getExitCode()).isZero();
	}
}
