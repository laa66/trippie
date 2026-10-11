package com.laa66.spatial.infrastructure.web;

/** Boundary-validation failure on a request's params or body — always a 400. */
class InvalidRequestException extends RuntimeException {

	InvalidRequestException(String message) {
		super(message);
	}
}
