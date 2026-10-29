package com.laa66.content;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the context with no web server and no database (the {@code test} profile turns startup
 * Flyway and the DB health contributor off). Without it, context startup would be verified only
 * behind Docker: the Postgres ITs are the only other classes that build a context, so a bad bean or
 * a malformed application.yaml would pass {@code make unit-test} green.
 */
@SpringBootTest
class ContentApplicationTests {

	@Test
	void contextLoads() {
	}
}
