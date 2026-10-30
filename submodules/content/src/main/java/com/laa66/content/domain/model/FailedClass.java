package com.laa66.content.domain.model;

/**
 * Why a generation attempt failed, mirroring {@code poi_content_failed_class_check}. The worker
 * (M4-10) classifies provider responses into these three; the retry budget does not depend on the
 * class, but the client UX (M4-14) does.
 */
public enum FailedClass {
	TRANSIENT, PERMANENT, QUOTA
}
