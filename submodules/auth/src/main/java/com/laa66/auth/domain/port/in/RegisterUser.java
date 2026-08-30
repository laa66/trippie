package com.laa66.auth.domain.port.in;

import java.util.UUID;

import com.laa66.auth.domain.model.RegistrationCommand;

/** Inbound port for the sign-up use case (flow 01). Returns the new user's id. */
public interface RegisterUser {

	UUID register(RegistrationCommand command);
}
