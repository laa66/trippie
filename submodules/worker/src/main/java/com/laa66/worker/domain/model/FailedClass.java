package com.laa66.worker.domain.model;

/**
 * Why a generation failed, with content's {@code failed_class} names: the write-back (M4-11) sends
 * the constant name as it is.
 */
public enum FailedClass {

	/** May succeed on a later attempt: provider 5xx, a per-minute 429, a timeout, an interrupted call. */
	TRANSIENT,

	/** Repeating the same prompt cannot change the answer and still costs a request. */
	PERMANENT,

	/** The daily request quota is spent; nothing succeeds before it resets. */
	QUOTA
}
