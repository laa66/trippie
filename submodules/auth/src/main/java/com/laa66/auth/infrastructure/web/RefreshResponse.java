package com.laa66.auth.infrastructure.web;

/** Refresh response body: the freshly minted access JWT only. The rotated refresh token is a cookie. */
record RefreshResponse(String accessToken) {
}
