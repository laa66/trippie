package com.laa66.auth.domain.model;

import java.util.UUID;

/**
 * Minimal account projection the verify/resend flows need after resolving an account by email:
 * the id keys the OTP/throttle machinery, {@code emailVerified} gates the idempotent no-op, and the
 * canonical stored {@code email} is the mailer recipient (not the raw, possibly odd-cased input).
 */
public record UserAccount(UUID id, String email, boolean emailVerified) {
}
