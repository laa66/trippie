package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /resend} payload: just the email to re-issue a verification code for. */
record ResendRequest(@NotBlank @Email String email) {
}
