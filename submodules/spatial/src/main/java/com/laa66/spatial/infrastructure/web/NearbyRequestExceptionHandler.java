package com.laa66.spatial.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates boundary-validation failures into RFC 9457 {@link ProblemDetail}. Extending
 * {@link ResponseEntityExceptionHandler} also covers Spring's own type-mismatch/missing-param
 * exceptions (e.g. a non-numeric {@code lat}) with the same 400 + ProblemDetail shape, with no
 * extra code here.
 */
@RestControllerAdvice
class NearbyRequestExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}
}
