package com.laa66.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.laa66.auth.support.EphemeralSigningKeyConfig;

@SpringBootTest
@Import(EphemeralSigningKeyConfig.class)
class AuthApplicationTests {

	@Test
	void contextLoads() {
	}
}
