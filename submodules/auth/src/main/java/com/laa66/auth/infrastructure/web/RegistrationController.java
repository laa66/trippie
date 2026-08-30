package com.laa66.auth.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.model.RegistrationCommand;
import com.laa66.auth.domain.port.in.RegisterUser;

import jakarta.validation.Valid;

/**
 * Public registration endpoint (flow 01). The gateway strips {@code /api/auth}, so this maps
 * {@code /register}. Boundary validation is on {@link RegisterRequest}; a 201 carries no body so
 * nothing about the credentials is echoed.
 */
@RestController
public class RegistrationController {

	private final RegisterUser registerUser;

	public RegistrationController(RegisterUser registerUser) {
		this.registerUser = registerUser;
	}

	@PostMapping("/register")
	public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
		registerUser.register(new RegistrationCommand(request.email(), request.password()));
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}
}
