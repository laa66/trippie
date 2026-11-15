package com.laa66.worker.domain.model;

/**
 * The part of spatial's POI detail the prompt may be built from. Spatial also serves
 * {@code description} and raw {@code tags}; they are not read at all (see {@link PoiPrompt}).
 * Either field may be null as spatial sends it; {@link PoiPrompt} decides whether that is a POI that
 * can be described.
 */
public record PoiDetail(String name, String category) {
}
