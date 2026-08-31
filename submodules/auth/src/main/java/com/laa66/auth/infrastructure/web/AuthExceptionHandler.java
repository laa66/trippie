package com.laa66.auth.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.InvalidOtpException;
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
}
