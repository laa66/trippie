package com.laa66.worker.domain.model;

import java.util.Map;

/**
 * The part of spatial's POI detail the prompt may be built from. Spatial also serves
 * {@code description}; it is not read at all. {@code tags} arrive whole, but only the keys
 * {@link PoiFact#from} names go further (see {@link PoiPrompt}). {@code name} and {@code category}
 * may be null as spatial sends them; {@link PoiPrompt} decides whether that is a POI that can be
 * described.
 */
public record PoiDetail(String name, String category, Map<String, String> tags) {

	public PoiDetail {
		tags = Map.copyOf(tags);
	}
}
