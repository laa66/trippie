package com.laa66.spatial.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.spatial.domain.model.InsufficientAccuracyException;

/**
 * Translates validation and policy failures into RFC 9457 {@link ProblemDetail} for every spatial
 * controller. Extending {@link ResponseEntityExceptionHandler} also covers Spring's own
 * type-mismatch / missing-param / missing-header / unreadable-body exceptions (a non-numeric
 * {@code lat}, a malformed {@code X-User-Id}, a body that is not JSON) with the same 400 +
 * ProblemDetail shape, with no extra code here.
 */
@RestControllerAdvice
class SpatialRequestExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	/**
	 * 422, not 400: the body is well-formed and every field is in range — the request is
	 * syntactically fine and semantically impossible to honour. The distinction is what lets the
	 * client tell "fix your request" from "wait for a better fix", which is the whole point of
	 * reporting the accuracy case at the request level instead of as a refused id.
	 */
	@ExceptionHandler(InsufficientAccuracyException.class)
	ProblemDetail handleInsufficientAccuracy(InsufficientAccuracyException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
	}
}
