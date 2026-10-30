package com.laa66.content.infrastructure.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.port.in.ReadPoiContent;

/**
 * Inbound HTTP adapter for the gated content read, and the only place a client receives text.
 * Nothing has been generated yet is a {@code 200 {"status":"ABSENT"}}, not a 404.
 *
 * <p>{@code X-User-Id} is required and bound as a {@link UUID}, the convention of spatial's
 * {@code NearbyLocationsController}: without it there is no user to ask the gate about, and a gateway
 * that stops setting it has to fail loudly with a 400 rather than lock every POI with a 403.
 *
 * <p>{@code poiId} is not validated here. An absent one is a 400; an empty or malformed one cannot
 * name a POI, so {@code SpatialPoiUnlocksAdapter} answers {@code false} without a call and it ends in
 * the same 403 as any other POI this user has not unlocked.
 */
@RestController
public class ContentController {

	private final ReadPoiContent readPoiContent;

	public ContentController(ReadPoiContent readPoiContent) {
		this.readPoiContent = readPoiContent;
	}

	@GetMapping("/content")
	public ContentResponse content(
			@RequestHeader("X-User-Id") UUID userId,
			@RequestParam String poiId,
			@RequestParam ContentMode mode) {

		return readPoiContent.read(userId, poiId, mode)
				.map(ContentResponse::from)
				.orElseGet(ContentResponse::absent);
	}
}
