package com.laa66.spatial.domain.model;

import java.util.Map;

/**
 * One POI as the by-id detail read projects it: the columns {@link LocationPoint} deliberately
 * leaves off the nearby path ({@code description}, {@code tags}) plus the identity and the category.
 *
 * <p>No distance and no coordinates: this projection answers "what is this POI", not "where am I
 * relative to it", and its only consumers are the detail endpoint and — from M4-11 — the worker
 * building a prompt. Keeping it separate from {@link LocationPoint} is also what keeps the nearby
 * payload the exact shape M6's cache key was frozen around.
 *
 * <p>{@code tags} is a flat string→string map by contract: the column is OSM's free-form JSONB, so
 * the adapter keeps the scalar entries and drops objects, arrays and JSON nulls rather than failing
 * a read because a mapper nested something. {@code name} and {@code description} are nullable in the
 * schema and stay nullable here.
 */
public record LocationDetail(
		String osmId,
		String name,
		Category category,
		String description,
		Map<String, String> tags) {
}
