package com.laa66.auth.infrastructure.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /forgot-password} payload: just the email to issue a reset code for. */
record ForgotPasswordRequest(@NotBlank @Email String email) {
}
