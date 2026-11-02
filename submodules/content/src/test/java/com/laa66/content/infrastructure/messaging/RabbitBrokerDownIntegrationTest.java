package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.support.AbstractPostgresIntegrationTest;
import com.laa66.content.support.PublishBudget;
import com.laa66.content.support.RabbitContainers;

/** Owns its broker, because the test kills it: the context here is useless afterwards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RabbitBrokerDownIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final RabbitMQContainer BROKER = RabbitContainers.started();

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	@DynamicPropertySource
	static void broker(DynamicPropertyRegistry registry) {
		RabbitContainers.register(registry, BROKER);
	}

	@Autowired
	private EventPublisher publisher;

	/** The first publish meets the dead connection, the later ones a refused connect. */
	@Test
	void everyPublishToAStoppedBrokerIsAnExceptionInsideTheBudget() {
		assertThatCode(() -> publisher.publish(TASK)).doesNotThrowAnyException();

		BROKER.stop();

		for (int attempt = 0; attempt < 3; attempt++) {
			PublishBudget.assertFailsWithinBudget(publisher, TASK);
		}
	}
}
