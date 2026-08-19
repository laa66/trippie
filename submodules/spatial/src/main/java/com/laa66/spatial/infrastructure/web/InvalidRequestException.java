package com.laa66.spatial.infrastructure.web;

/** Boundary-validation failure on the {@code /locations/nearby} query params — always a 400. */
class InvalidRequestException extends RuntimeException {

	InvalidRequestException(String message) {
		super(message);
	}
}
