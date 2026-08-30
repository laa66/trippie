package com.laa66.auth.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code app_user} JPA entity. {@code created_at} is intentionally unmapped so the DB default fills
 * it — the schema owns that value, not the application.
 */
@Entity
@Table(name = "app_user")
class AppUser {

	@Id
	private UUID id;

	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "email_verified")
	private boolean emailVerified;

	protected AppUser() {
	}

	AppUser(UUID id, String email, String passwordHash) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.emailVerified = false;
	}

	UUID getId() {
		return id;
	}
}
