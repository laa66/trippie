package com.laa66.content.domain.model;

/**
 * The broker did not confirm taking responsibility for a message. This says "not known to be
 * queued", not "not queued": an unanswered publish may still reach the queue later and be paid for.
 */
public class EventPublicationException extends RuntimeException {

	public EventPublicationException(String message) {
		super(message);
	}

	public EventPublicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
