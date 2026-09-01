package com.laa66.auth.infrastructure.web;

/** Login response body: the access JWT only. The refresh token is delivered solely via cookie. */
record LoginResponse(String accessToken) {
}
