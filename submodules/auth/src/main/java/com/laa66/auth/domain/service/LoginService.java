package com.laa66.auth.domain.service;

import java.util.Optional;

import com.laa66.auth.domain.model.EmailNotVerifiedException;
import com.laa66.auth.domain.model.InvalidCredentialsException;
import com.laa66.auth.domain.model.LoginResult;
import com.laa66.auth.domain.model.UserCredentials;
import com.laa66.auth.domain.port.in.LoginUser;
import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Login use case (flow 02), framework-free. Order matters for anti-enumeration:
 *
 * <ol>
 * <li>The BCrypt verify runs on EVERY attempt — against a dummy hash of matching cost when the email
 * is unknown — so an unknown email and a wrong password take the same time and return the same 401.
 * <li>The verified-gate (403) is checked only AFTER a successful password match, so it can never
 * reveal an unverified account to anyone who does not already know the password.
 * </ol>
 *
 * <p>The token {@code sub} always comes from the looked-up account, never from the request.
 */
public class LoginService implements LoginUser {

	private final UserRepository users;
	private final PasswordHasher passwordHasher;
	private final MintAccessToken mintAccessToken;
	private final RefreshTokenStore refreshTokenStore;

	/** A real BCrypt hash of the configured strength, used purely to spend equal work on unknown emails. */
	private final String dummyHash;

	public LoginService(UserRepository users, PasswordHasher passwordHasher, MintAccessToken mintAccessToken,
			RefreshTokenStore refreshTokenStore) {
		this.users = users;
		this.passwordHasher = passwordHasher;
		this.mintAccessToken = mintAccessToken;
		this.refreshTokenStore = refreshTokenStore;
		this.dummyHash = passwordHasher.hash("constant-work-placeholder");
	}

	@Override
	public LoginResult login(String email, String rawPassword) {
		Optional<UserCredentials> found = users.findCredentialsByEmail(email);
		String hashToCheck = found.map(UserCredentials::passwordHash).orElse(dummyHash);

		// Always runs, even for an unknown email, so timing does not leak account existence.
		boolean passwordMatches = passwordHasher.verify(rawPassword, hashToCheck);

		if (found.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}

		UserCredentials user = found.get();
		if (!user.emailVerified()) {
			throw new EmailNotVerifiedException();
		}

		String accessToken = mintAccessToken.mint(user.id());
		String refreshToken = refreshTokenStore.issueNewFamily(user.id());
		return new LoginResult(accessToken, refreshToken);
	}
}
