package com.laa66.auth.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.auth.domain.model.EmailAlreadyExistsException;

/**
 * Translates auth boundary failures into RFC 9457 {@link ProblemDetail}. Extending
 * {@link ResponseEntityExceptionHandler} gives bean-validation failures
 * ({@code MethodArgumentNotValidException} — malformed email, weak/empty password) the same
 * 400 + ProblemDetail shape with no extra code, and never surfaces the submitted password value.
 */
@RestControllerAdvice
class AuthExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(EmailAlreadyExistsException.class)
	ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}
}
