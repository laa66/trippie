package com.laa66.content.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.model.UnsupportedContentModeException;

/**
 * Translates gate and policy failures into RFC 9457 {@link ProblemDetail}. Extending
 * {@link ResponseEntityExceptionHandler} covers Spring's own missing-param, missing-header and
 * type-mismatch exceptions with the same 400 shape.
 *
 * <p>Every {@code detail} here is a constant. The 403 must not vary with anything about the POI, and
 * the 503 must not read its exception: the message and the cause describe the call to spatial,
 * which the adapter has already logged and a client has no use for.
 */
@RestControllerAdvice
class ContentRequestExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(PoiNotUnlockedException.class)
	ProblemDetail handlePoiNotUnlocked() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "POI is not unlocked");
	}

	@ExceptionHandler(PoiUnlockStateUnavailableException.class)
	ProblemDetail handleUnlockStateUnavailable() {
		return ProblemDetail.forStatusAndDetail(
				HttpStatus.SERVICE_UNAVAILABLE, "Unlock state is temporarily unavailable");
	}

	@ExceptionHandler(UnsupportedContentModeException.class)
	ProblemDetail handleUnsupportedMode() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Only TEXT content is available");
	}
}
