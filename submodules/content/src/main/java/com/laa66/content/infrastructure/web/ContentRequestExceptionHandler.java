package com.laa66.content.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.PoiNotUnlockedException;
import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;
import com.laa66.content.domain.model.UnsupportedContentModeException;

/**
 * Translates gate and policy failures into RFC 9457 {@link ProblemDetail}. Extending
 * {@link ResponseEntityExceptionHandler} covers Spring's own missing-param, missing-header and
 * type-mismatch exceptions with the same 400 shape.
 *
 * <p>Every {@code detail} here is a constant. The 403 must not vary with anything about the POI, and
 * the 503s must not read their exceptions: the message and the cause describe the call to spatial
 * or to the broker, which belongs in the log and is of no use to a client.
 */
@RestControllerAdvice
class ContentRequestExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ContentRequestExceptionHandler.class);

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

	/** The publisher does not log, so this is the one trace of a task the broker did not take. */
	@ExceptionHandler(EventPublicationException.class)
	ProblemDetail handleEventPublication(EventPublicationException ex) {
		log.warn("generation task not published", ex);
		return ProblemDetail.forStatusAndDetail(
				HttpStatus.SERVICE_UNAVAILABLE, "Content generation is temporarily unavailable");
	}
}
