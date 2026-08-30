package com.laa66.auth.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code email} is CITEXT, so this derived query folds case at the DB level. */
interface AppUserJpaRepository extends JpaRepository<AppUser, UUID> {

	boolean existsByEmail(String email);
}
