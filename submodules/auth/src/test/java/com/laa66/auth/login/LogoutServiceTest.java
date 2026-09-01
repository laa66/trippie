package com.laa66.auth.login;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.laa66.auth.domain.port.out.AccessTokenDenylist;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.service.LogoutService;

/**
 * Unit proof of the logout orchestration with mocked ports: both revocations fire on a normal logout;
 * an absent refresh token skips the family delete but still denylists the access {@code jti}; the
 * {@code jti} and TTL are passed straight through (the web adapter already trusted them). Each
 * assertion turns red under the obvious mutation (dropping a revocation, or gating the denylist on the
 * cookie).
 */
class LogoutServiceTest {

	private static final Duration SKEW = Duration.ofSeconds(60);

	private RefreshTokenStore store;
	private AccessTokenDenylist denylist;
	private LogoutService service;

	@BeforeEach
	void setUp() {
		store = mock(RefreshTokenStore.class);
		denylist = mock(AccessTokenDenylist.class);
		service = new LogoutService(store, denylist, SKEW);
	}

	@Test
	void logout_revokesFamily_andDenylistsJti_withSkewAddedToRemainingLife() {
		service.logout("raw-refresh", "jti-123", Duration.ofMinutes(12));

		verify(store).deleteByToken("raw-refresh");
		// The denylist entry outlives the token by the gateway skew so it covers exp+skew.
		verify(denylist).deny("jti-123", Duration.ofMinutes(12).plus(SKEW));
	}

	@Test
	void logout_withoutRefreshToken_stillDenylistsJti_andNeverTouchesStore() {
		service.logout(null, "jti-123", Duration.ofMinutes(12));

		verify(denylist).deny("jti-123", Duration.ofMinutes(12).plus(SKEW));
		verifyNoInteractions(store);
	}

	@Test
	void logout_withBlankRefreshToken_skipsFamilyDelete() {
		service.logout("   ", "jti-123", Duration.ofSeconds(30));

		verify(store, never()).deleteByToken(org.mockito.ArgumentMatchers.any());
		verify(denylist).deny("jti-123", Duration.ofSeconds(30).plus(SKEW));
	}
}
