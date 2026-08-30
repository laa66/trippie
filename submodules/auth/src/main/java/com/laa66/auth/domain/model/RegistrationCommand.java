package com.laa66.auth.domain.model;

/**
 * A registration request as the domain sees it: the email is already format-validated at the web
 * boundary, the password is still raw (hashing is the domain's job).
 */
public record RegistrationCommand(String email, String rawPassword) {
}
