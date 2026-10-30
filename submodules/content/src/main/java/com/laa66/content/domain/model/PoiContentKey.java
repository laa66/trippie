package com.laa66.content.domain.model;

/**
 * The identity of one piece of generated content: the {@code poi_content} primary key
 * {@code (osm_id, mode, lang)}.
 *
 * <p>{@code osmId} carries its element type ({@code node/42}), so it is text and not a number.
 * {@code lang} is a dimension from day one although content is Polish today — the server supplies
 * it (M4 decision 6) and the client never sends it.
 *
 * <p>No validation here: the shape rules for {@code mode} and {@code lang} are enforced by the
 * table's CHECKs, which are the only enforcement point a Java service, a Go loader and a worker
 * share. The cost is asymmetric and worth knowing — a malformed {@code lang} is a loud constraint
 * violation on a write but a silent empty result on a read — which is exactly why the value is a
 * server-side constant and not request input.
 */
public record PoiContentKey(String osmId, ContentMode mode, String lang) {
}
