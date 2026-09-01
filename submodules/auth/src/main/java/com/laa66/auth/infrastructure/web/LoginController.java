package com.laa66.auth.infrastructure.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.model.LoginResult;
import com.laa66.auth.domain.port.in.LoginUser;

import jakarta.validation.Valid;

/**
 * Public login endpoint (flow 02). The gateway strips {@code /api/auth}, so this maps {@code /login}.
 * On success it returns {@code {accessToken}} in the body and sets two cookies: the httpOnly refresh
 * token and the readable csrf token. Failures are mapped by {@link AuthExceptionHandler}.
 */
@RestController
public class LoginController {

	private final LoginUser loginUser;
	private final AuthCookies cookies;

	public LoginController(LoginUser loginUser, AuthCookies cookies) {
		this.loginUser = loginUser;
		this.cookies = cookies;
	}

	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		LoginResult result = loginUser.login(request.email(), request.password());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(result.refreshToken()).toString())
				.header(HttpHeaders.SET_COOKIE, cookies.csrfCookie().toString())
				.body(new LoginResponse(result.accessToken()));
	}
}
