package com.laa66.content.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.PoiContent;

/**
 * One shape for every state, so the client has one {@code switch}: {@code text} is present exactly
 * on {@code COMPLETED} and {@code failedClass} exactly on {@code FAILED}. A {@code PROCESSING} or
 * {@code FAILED} row may still carry text from an earlier success, and it is not served: the client
 * contract (M4-12) ties {@code text} to {@code COMPLETED}.
 *
 * <p>{@code ABSENT} is a state of this response only: no such row status exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ContentResponse(String status, String text, FailedClass failedClass) {

	private static final String ABSENT = "ABSENT";

	static ContentResponse absent() {
		return new ContentResponse(ABSENT, null, null);
	}

	static ContentResponse from(PoiContent content) {
		String status = content.status().name();
		return switch (content.status()) {
			case COMPLETED -> new ContentResponse(status, content.text(), null);
			case PROCESSING -> new ContentResponse(status, null, null);
			case FAILED -> new ContentResponse(status, null, content.failedClass());
		};
	}
}
