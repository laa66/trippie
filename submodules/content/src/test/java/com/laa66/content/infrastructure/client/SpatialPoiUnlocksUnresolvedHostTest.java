package com.laa66.content.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.infrastructure.config.ContentConfig;

/**
 * A host nobody can resolve; {@code .invalid} is reserved by RFC 6761. The outcome is the same with
 * or without a network, but the route is not: measured, with a resolver that stalls the budget cuts
 * the call at about 1 s, and with no network the JDK client fails at once. So there is no lower
 * bound and no assertion on the cause.
 */
@SpringBootTest(properties = "trippie.spatial.uri=http://spatial.invalid:8080")
class SpatialPoiUnlocksUnresolvedHostTest {

	@Autowired
	private PoiUnlocks poiUnlocks;

	@Test
	@Timeout(20)
	void unresolvableHost_isUnavailableWithinTheBudget() {
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> poiUnlocks.isUnlocked(UUID.randomUUID(), "node/42"))
				.isInstanceOf(PoiUnlockStateUnavailableException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
				.isLessThan(ContentConfig.SPATIAL_TIMEOUT.multipliedBy(10));
	}
}
