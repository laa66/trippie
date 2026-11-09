package com.laa66.worker.domain.model;

/**
 * The provider refused the credential itself (a wrong, revoked or restricted key), not the POI.
 *
 * <p>Deliberately not a {@link TextGenerationException}: it has no {@link FailedClass}, so nothing
 * that records, retries or dead-letters a POI failure can take it for one. Every task would fail the
 * same way until the key is fixed, so the worker stops consuming and puts the task back in the queue
 * instead. The message never carries the credential.
 */
public class ProviderCredentialRejectedException extends RuntimeException {

	public ProviderCredentialRejectedException(String message) {
		super(message);
	}
}
