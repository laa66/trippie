package com.laa66.content.infrastructure.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.ContentStatus;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.GenerationStatus;
import com.laa66.content.domain.port.in.GenerateContent;

/**
 * Inbound HTTP adapter for generation requests. It answers with a status only: a {@code 202} while a
 * task is queued or running, a {@code 200} when the answer is final. {@code failedClass} is present
 * exactly on {@code FAILED}, as in {@code GET /content}, which is where the text is read from.
 *
 * <p>{@code X-User-Id} follows {@link ContentController}. A {@code poiId} that is empty or malformed is
 * not checked here either: it cannot name a POI and ends in the ordinary 403.
 */
@RestController
public class ContentGenerationController {

	private final GenerateContent generateContent;

	public ContentGenerationController(GenerateContent generateContent) {
		this.generateContent = generateContent;
	}

	@PostMapping("/content/generate")
	public ResponseEntity<GenerateResponse> generate(
			@RequestHeader("X-User-Id") UUID userId,
			@RequestBody GenerateRequest request) {

		if (request.poiId() == null || request.mode() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "poiId and mode are required");
		}
		GenerationStatus answer = generateContent.generate(userId, request.poiId(), request.mode());
		return ResponseEntity.status(answer.status() == ContentStatus.PROCESSING ? HttpStatus.ACCEPTED : HttpStatus.OK)
				.body(new GenerateResponse(answer.status(), answer.failedClass()));
	}

	record GenerateRequest(String poiId, ContentMode mode) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GenerateResponse(ContentStatus status, FailedClass failedClass) {
	}
}
