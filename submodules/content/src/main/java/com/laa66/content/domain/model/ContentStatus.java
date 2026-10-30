package com.laa66.content.domain.model;

/**
 * The three row states of {@code poi_content}, mirroring its {@code poi_content_status_check}.
 *
 * <p>"No row yet" is deliberately not a value here: the absence of a row is expressed by an empty
 * {@code Optional} from the read, and the {@code ABSENT} the HTTP contract (M4-06) serves to the
 * client is a response shape, not a row state.
 */
public enum ContentStatus {
	PROCESSING, COMPLETED, FAILED
}
