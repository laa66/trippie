package com.laa66.auth.domain.port.in;

import com.laa66.auth.domain.model.LoginResult;

/** Inbound port for the login use case (flow 02): authenticate, then issue access + refresh. */
public interface LoginUser {

	LoginResult login(String email, String rawPassword);
}
