package com.laa66.content.domain.model;

/**
 * Nobody can currently say whether the POI is unlocked. This is the absence of an answer, not a
 * negative one: it must surface as a 503 and never as a 403.
 */
public class PoiUnlockStateUnavailableException extends RuntimeException {

	public PoiUnlockStateUnavailableException(String message) {
		super(message);
	}

	public PoiUnlockStateUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
