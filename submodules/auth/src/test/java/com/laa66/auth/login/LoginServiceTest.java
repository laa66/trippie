package com.laa66.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.laa66.auth.domain.model.EmailNotVerifiedException;
import com.laa66.auth.domain.model.InvalidCredentialsException;
import com.laa66.auth.domain.model.LoginResult;
import com.laa66.auth.domain.model.UserCredentials;
import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.port.out.UserRepository;
import com.laa66.auth.domain.service.LoginService;

/**
 * Unit proof of the login rules with mocked ports: constant-work (BCrypt runs even for an unknown
 * email), the identical-401 for unknown-vs-wrong-password, the verified-gate 403 gated behind a
 * correct password, and {@code sub} taken from the account not the request. Each assertion turns red
 * under the obvious mutation.
 */
class LoginServiceTest {

	private static final String DUMMY_HASH = "{bcrypt}$2a$12$dummydummydummydummydu";

	private UserRepository users;
	private PasswordHasher hasher;
	private MintAccessToken minter;
	private RefreshTokenStore refreshStore;
	private LoginService service;

	@BeforeEach
	void setUp() {
		users = org.mockito.Mockito.mock(UserRepository.class);
		hasher = org.mockito.Mockito.mock(PasswordHasher.class);
		minter = org.mockito.Mockito.mock(MintAccessToken.class);
		refreshStore = org.mockito.Mockito.mock(RefreshTokenStore.class);
		// The service hashes a placeholder once in its constructor for constant-work.
		when(hasher.hash(any())).thenReturn(DUMMY_HASH);
		service = new LoginService(users, hasher, minter, refreshStore);
	}

	@Test
	void validVerifiedCredentials_issueAccessAndRefresh_withSubFromAccount() {
		UUID userId = UUID.randomUUID();
		when(users.findCredentialsByEmail("user@example.com"))
				.thenReturn(Optional.of(new UserCredentials(userId, "{bcrypt}$realhash", true)));
		when(hasher.verify("pw", "{bcrypt}$realhash")).thenReturn(true);
		when(minter.mint(userId)).thenReturn("access.jwt");
		when(refreshStore.issueNewFamily(userId)).thenReturn("raw-refresh");

		LoginResult result = service.login("user@example.com", "pw");

		assertThat(result.accessToken()).isEqualTo("access.jwt");
		assertThat(result.refreshToken()).isEqualTo("raw-refresh");
		verify(minter).mint(userId);
		verify(refreshStore).issueNewFamily(userId);
	}

	@Test
	void unknownEmail_throws401_andStillRunsBcryptAgainstDummy() {
		when(users.findCredentialsByEmail("ghost@example.com")).thenReturn(Optional.empty());
		when(hasher.verify(eq("pw"), any())).thenReturn(false);

		assertThatThrownBy(() -> service.login("ghost@example.com", "pw"))
				.isInstanceOf(InvalidCredentialsException.class);

		// Constant-work: verify must run even with no account, against the dummy hash.
		verify(hasher).verify("pw", DUMMY_HASH);
		verifyNoInteractions(minter, refreshStore);
	}

	@Test
	void wrongPassword_throwsSame401AsUnknownEmail() {
		when(users.findCredentialsByEmail("user@example.com"))
				.thenReturn(Optional.of(new UserCredentials(UUID.randomUUID(), "{bcrypt}$realhash", true)));
		when(hasher.verify("bad", "{bcrypt}$realhash")).thenReturn(false);

		assertThatThrownBy(() -> service.login("user@example.com", "bad"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(minter, never()).mint(any());
		verify(refreshStore, never()).issueNewFamily(any());
	}

	@Test
	void correctPasswordButUnverified_throws403_notMintingTokens() {
		UUID userId = UUID.randomUUID();
		when(users.findCredentialsByEmail("user@example.com"))
				.thenReturn(Optional.of(new UserCredentials(userId, "{bcrypt}$realhash", false)));
		when(hasher.verify("pw", "{bcrypt}$realhash")).thenReturn(true);

		assertThatThrownBy(() -> service.login("user@example.com", "pw"))
				.isInstanceOf(EmailNotVerifiedException.class);

		verify(minter, never()).mint(any());
		verify(refreshStore, never()).issueNewFamily(any());
	}

	@Test
	void unverifiedAccountWithWrongPassword_throws401_notVerificationGate() {
		// Pins the gate ORDER: the 403 verified-gate must never fire before the password is proven.
		// An unverified account presented with a WRONG password must be indistinguishable from any
		// other bad-credentials attempt (401), so it can never become an enumeration oracle.
		when(users.findCredentialsByEmail("user@example.com"))
				.thenReturn(Optional.of(new UserCredentials(UUID.randomUUID(), "{bcrypt}$realhash", false)));
		when(hasher.verify("bad", "{bcrypt}$realhash")).thenReturn(false);

		assertThatThrownBy(() -> service.login("user@example.com", "bad"))
				.isInstanceOf(InvalidCredentialsException.class)
				.isNotInstanceOf(EmailNotVerifiedException.class);

		verifyNoInteractions(minter, refreshStore);
	}

	@Test
	void mint_usesAccountId_notAnythingFromTheRequest() {
		UUID accountId = UUID.randomUUID();
		when(users.findCredentialsByEmail("user@example.com"))
				.thenReturn(Optional.of(new UserCredentials(accountId, "{bcrypt}$realhash", true)));
		when(hasher.verify(any(), eq("{bcrypt}$realhash"))).thenReturn(true);
		when(minter.mint(any())).thenReturn("access.jwt");
		when(refreshStore.issueNewFamily(any())).thenReturn("raw");

		service.login("user@example.com", "pw");

		ArgumentCaptor<UUID> sub = ArgumentCaptor.forClass(UUID.class);
		verify(minter, times(1)).mint(sub.capture());
		assertThat(sub.getValue()).isEqualTo(accountId);
	}
}
