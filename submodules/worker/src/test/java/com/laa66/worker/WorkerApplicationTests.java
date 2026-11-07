package com.laa66.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the context with no broker reachable: the listener container keeps retrying in the
 * background and must not fail the boot. Without this, a bad bean would surface only behind Docker.
 */
@SpringBootTest
class WorkerApplicationTests {

	@Test
	void contextLoads() {
	}
}
