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

	/**
	 * 404 whose {@code detail} is a constant: the requested id is reflected nowhere a client has to
	 * parse, and adding it would say nothing the caller does not already know.
	 *
	 * <p><strong>The response body is not id-free, and the difference is worth stating exactly,
	 * because the first version of this javadoc claimed otherwise.</strong> Measured: Spring fills a
	 * {@link ProblemDetail}'s {@code instance} with the request URI when it is left null, so
	 * {@code GET /locations/node/999} answers {@code "instance": "/locations/node/999"} — an arbitrary
	 * path segment, of arbitrary length, echoed back. That is deliberate and kept: it is RFC 9457's
	 * own field for "which request failed", every other problem response from this advice carries it
	 * (a malformed {@code lat} echoes its URI the same way), and it is inert — the media type is
	 * {@code application/problem+json} and the value is JSON-escaped, so it is a diagnostic and not a
	 * rendering surface. Overriding it here alone would make the 404 the one response in the module
	 * without it, to hide an id the client just sent.
	 *
	 * <p>{@code LocationDetailControllerTest} pins both halves — the constant detail and the echoed
	 * instance — so neither can change silently.
	 */
	@ExceptionHandler(LocationNotFoundException.class)
	ProblemDetail handleLocationNotFound(LocationNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No POI with that id");
	}

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
