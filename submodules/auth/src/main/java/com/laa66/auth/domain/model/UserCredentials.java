package com.laa66.auth.domain.model;

import java.util.UUID;

/**
 * Credential projection the login flow needs: the id becomes the token {@code sub}, the stored
 * BCrypt {@code passwordHash} is checked against the submitted password, and {@code emailVerified}
 * drives the verified-gate. The hash never leaves the domain/persistence boundary — it is never put
 * in a DTO or a response.
 */
public record UserCredentials(UUID id, String passwordHash, boolean emailVerified) {
}
