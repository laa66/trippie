package com.laa66.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.laa66.worker.infrastructure.messaging.GenerationTaskListener;

/**
 * Starts the context with no broker reachable: the listener container keeps retrying in the
 * background and must not fail the boot. Without this, a bad bean would surface only behind Docker.
 */
@SpringBootTest
class WorkerApplicationTests {

	@Autowired
	private RabbitListenerEndpointRegistry listeners;

	@Test
	void contextLoads() {
	}

	/** The contrast to {@code NoApiKeyStartupTest}: with a key file (the test profile's fake one) it starts. */
	@Test
	void withAKeyTheListenerIsStarted() {
		assertThat(listeners.getListenerContainer(GenerationTaskListener.LISTENER_ID).isRunning()).isTrue();
	}
}
