package com.laa66.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.RefreshResult;
import com.laa66.auth.domain.model.RotatedToken;
import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.service.RefreshService;

/**
 * Unit proof of the refresh orchestration with mocked ports: a successful rotation mints an access
 * token whose {@code sub} is the account id RETURNED BY THE ROTATION (not any request input), and a
 * failed rotation (reuse/expiry/absent) never mints a token. Each assertion turns red under the
 * obvious mutation (minting before rotating, or from a wrong id).
 */
class RefreshServiceTest {

	private RefreshTokenStore store;
	private MintAccessToken minter;
	private RefreshService service;

	@BeforeEach
	void setUp() {
		store = org.mockito.Mockito.mock(RefreshTokenStore.class);
		minter = org.mockito.Mockito.mock(MintAccessToken.class);
		service = new RefreshService(store, minter);
	}

	@Test
	void validRotation_mintsAccessForRotatedUserId_andReturnsRotatedToken() {
		UUID userId = UUID.randomUUID();
		when(store.rotate("old-raw")).thenReturn(new RotatedToken("new-raw", userId));
		when(minter.mint(userId)).thenReturn("new.access.jwt");

		RefreshResult result = service.refresh("old-raw");

		assertThat(result.accessToken()).isEqualTo("new.access.jwt");
		assertThat(result.refreshToken()).isEqualTo("new-raw");
		verify(minter).mint(userId);
	}

	@Test
	void invalidRotation_propagates401_andNeverMints() {
		when(store.rotate("bad-raw")).thenThrow(new InvalidRefreshTokenException());

		assertThatThrownBy(() -> service.refresh("bad-raw"))
				.isInstanceOf(InvalidRefreshTokenException.class);

		verify(minter, never()).mint(any());
	}

	@Test
	void mintFailure_neverFabricatesResult() {
		// Guards the order: rotation happened, but if minting blows up the caller sees the failure,
		// never a half-built result.
		when(store.rotate("old-raw")).thenReturn(new RotatedToken("new-raw", UUID.randomUUID()));
		when(minter.mint(any())).thenThrow(new IllegalStateException("signing key unavailable"));

		assertThatThrownBy(() -> service.refresh("old-raw")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rotateRunsBeforeAnyMint() {
		when(store.rotate(any())).thenThrow(new InvalidRefreshTokenException());

		assertThatThrownBy(() -> service.refresh("x")).isInstanceOf(InvalidRefreshTokenException.class);

		verifyNoInteractions(minter);
	}
}
