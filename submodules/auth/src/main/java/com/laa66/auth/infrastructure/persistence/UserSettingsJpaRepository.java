package com.laa66.auth.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserSettingsJpaRepository extends JpaRepository<UserSettings, UUID> {
}
