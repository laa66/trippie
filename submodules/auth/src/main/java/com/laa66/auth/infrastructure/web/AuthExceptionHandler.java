package com.laa66.auth.infrastructure.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.EmailNotVerifiedException;
import com.laa66.auth.domain.model.InvalidAccessTokenException;
import com.laa66.auth.domain.model.InvalidCredentialsException;
import com.laa66.auth.domain.model.InvalidOtpException;
import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.OtpThrottledException;

/**
 * Translates auth boundary failures into RFC 9457 {@link ProblemDetail}. Extending
 * {@link ResponseEntityExceptionHandler} gives bean-validation failures
 * ({@code MethodArgumentNotValidException} — malformed email, weak/empty password, bad OTP shape)
 * the same 400 + ProblemDetail shape with no extra code, and never surfaces the submitted password.
 */
@RestControllerAdvice
class AuthExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(EmailAlreadyExistsException.class)
	ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	/**
	 * One uniform 400 for every verify failure (wrong/expired/absent code, attempt cap, unknown
	 * email), so {@code /verify} cannot be used to enumerate accounts.
	 */
	@ExceptionHandler(InvalidOtpException.class)
	ProblemDetail handleInvalidOtp(InvalidOtpException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(OtpThrottledException.class)
	ProblemDetail handleThrottled(OtpThrottledException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
	}

	/** Unknown email and wrong password share this identical 401 — no account enumeration. */
	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	/** Distinct 403 (own {@code type}) so the frontend can route a login attempt to the verify screen. */
	@ExceptionHandler(EmailNotVerifiedException.class)
	ProblemDetail handleEmailNotVerified(EmailNotVerifiedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setType(URI.create("urn:trippie:auth:email-not-verified"));
		problem.setTitle("Email verification required");
		return problem;
	}

	/**
	 * Uniform 401 for every unusable refresh token — absent, expired, unknown, or reused. A reuse also
	 * revokes the family in the store, but the response is identical to the others: no oracle that
	 * would tell an attacker their stolen token was already spent.
	 */
	@ExceptionHandler(InvalidRefreshTokenException.class)
	ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	/**
	 * Uniform 401 for a logout whose bearer access token is absent, malformed, wrongly signed,
	 * wrong-issuer, or expired — logout re-verifies the token to trust its {@code jti}/{@code exp}.
	 */
	@ExceptionHandler(InvalidAccessTokenException.class)
	ProblemDetail handleInvalidAccessToken(InvalidAccessTokenException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	/** Distinct 403 (own {@code type}) for a failed double-submit CSRF check on /refresh (and /logout). */
	@ExceptionHandler(CsrfValidationException.class)
	ProblemDetail handleCsrf(CsrfValidationException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setType(URI.create("urn:trippie:auth:csrf"));
		problem.setTitle("CSRF validation failed");
		return problem;
	}
}
