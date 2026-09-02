package com.laa66.auth.infrastructure.config;

import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.auth.domain.port.in.GetUserSettings;
import com.laa66.auth.domain.port.in.LoginUser;
import com.laa66.auth.domain.port.in.Logout;
import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.domain.port.in.RefreshTokens;
import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.domain.port.in.RequestPasswordReset;
import com.laa66.auth.domain.port.in.ResendVerification;
import com.laa66.auth.domain.port.in.ResetPassword;
import com.laa66.auth.domain.port.in.UpdateUserSettings;
import com.laa66.auth.domain.port.in.VerifyEmail;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.AccessTokenDenylist;
import com.laa66.auth.domain.port.out.OtpThrottle;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.port.out.UserRepository;
import com.laa66.auth.domain.service.ForgotPasswordService;
import com.laa66.auth.domain.service.LoginService;
import com.laa66.auth.domain.service.LogoutService;
import com.laa66.auth.domain.service.OtpGenerator;
import com.laa66.auth.domain.service.OtpIssuer;
import com.laa66.auth.domain.service.RefreshService;
import com.laa66.auth.domain.service.RegistrationService;
import com.laa66.auth.domain.service.ResendVerificationService;
import com.laa66.auth.domain.service.ResetPasswordService;
import com.laa66.auth.domain.service.UserSettingsService;
import com.laa66.auth.domain.service.VerifyEmailService;

/** Wires the framework-free domain services to their outbound adapters. */
@Configuration
class AuthConfig {

	@Bean
	SecureRandom secureRandom() {
		return new SecureRandom();
	}

	/**
	 * Off-request-thread dispatcher for forgot-password OTP issuance, so a known vs. unknown email
	 * cannot be told apart by response latency. Virtual threads (the service already runs on them), and
	 * Spring infers {@code shutdown()} as the destroy method on context close. Typed as
	 * {@link ExecutorService} so it never collides with Boot's own {@code Executor}/task-executor bean.
	 */
	@Bean
	ExecutorService otpDispatchExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	OtpGenerator otpGenerator(SecureRandom secureRandom) {
		return new OtpGenerator(secureRandom);
	}

	@Bean
	OtpIssuer otpIssuer(OtpGenerator otpGenerator, OtpStore otpStore, OtpMailer otpMailer) {
		return new OtpIssuer(otpGenerator, otpStore, otpMailer);
	}

	@Bean
	RegisterUser registerUser(UserRepository userRepository, PasswordHasher passwordHasher, OtpIssuer otpIssuer) {
		return new RegistrationService(userRepository, passwordHasher, otpIssuer);
	}

	@Bean
	VerifyEmail verifyEmail(UserRepository userRepository, OtpStore otpStore) {
		return new VerifyEmailService(userRepository, otpStore);
	}

	@Bean
	ResendVerification resendVerification(UserRepository userRepository, OtpThrottle otpThrottle,
			OtpIssuer otpIssuer) {
		return new ResendVerificationService(userRepository, otpThrottle, otpIssuer);
	}

	@Bean
	RequestPasswordReset requestPasswordReset(UserRepository userRepository, OtpThrottle otpThrottle,
			OtpIssuer otpIssuer, ExecutorService otpDispatchExecutor) {
		return new ForgotPasswordService(userRepository, otpThrottle, otpIssuer, otpDispatchExecutor);
	}

	@Bean
	ResetPassword resetPassword(UserRepository userRepository, OtpStore otpStore, PasswordHasher passwordHasher,
			RefreshTokenStore refreshTokenStore) {
		return new ResetPasswordService(userRepository, otpStore, passwordHasher, refreshTokenStore);
	}

	@Bean
	LoginUser loginUser(UserRepository userRepository, PasswordHasher passwordHasher,
			MintAccessToken mintAccessToken, RefreshTokenStore refreshTokenStore) {
		return new LoginService(userRepository, passwordHasher, mintAccessToken, refreshTokenStore);
	}

	@Bean
	RefreshTokens refreshTokens(RefreshTokenStore refreshTokenStore, MintAccessToken mintAccessToken) {
		return new RefreshService(refreshTokenStore, mintAccessToken);
	}

	@Bean
	Logout logout(RefreshTokenStore refreshTokenStore, AccessTokenDenylist accessTokenDenylist,
			JwtProperties jwtProperties) {
		return new LogoutService(refreshTokenStore, accessTokenDenylist, jwtProperties.clockSkew());
	}

	@Bean
	UserSettingsService userSettingsService(UserRepository userRepository) {
		return new UserSettingsService(userRepository);
	}

	@Bean
	GetUserSettings getUserSettings(UserSettingsService userSettingsService) {
		return userSettingsService;
	}

	@Bean
	UpdateUserSettings updateUserSettings(UserSettingsService userSettingsService) {
		return userSettingsService;
	}
}
