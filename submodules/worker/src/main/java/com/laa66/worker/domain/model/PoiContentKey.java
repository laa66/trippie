package com.laa66.worker.domain.model;

/**
 * The {@code poi_content} primary key {@code (osm_id, mode, lang)} as content sends it. The worker
 * hands it back unchanged on the write-back (M4-11) and never builds one itself.
 */
public record PoiContentKey(String osmId, ContentMode mode, String lang) {
}
