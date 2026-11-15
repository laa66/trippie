package com.laa66.worker.domain.model;

/**
 * The provider refused the credential itself (a wrong, revoked or restricted key), not the POI. The
 * message never carries the credential.
 */
public class ProviderCredentialRejectedException extends ConsumerMustStopException {

	public ProviderCredentialRejectedException(String message) {
		super(message, "put a valid key into the key file and restart the worker");
	}
}
