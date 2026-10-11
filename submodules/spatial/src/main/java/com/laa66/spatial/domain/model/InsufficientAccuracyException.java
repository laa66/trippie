package com.laa66.spatial.domain.model;

/**
 * The asserted position is not accurate enough to unlock anything. A whole-request failure, not a
 * per-id one: one position claim is either good enough to be measured against or it is not, so
 * there is nothing to report per POI and nothing is written.
 */
public class InsufficientAccuracyException extends RuntimeException {

	public InsufficientAccuracyException(String message) {
		super(message);
	}
}
