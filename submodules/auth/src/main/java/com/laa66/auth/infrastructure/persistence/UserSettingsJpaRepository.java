package com.laa66.auth.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserSettingsJpaRepository extends JpaRepository<UserSettings, UUID> {

	@Modifying
	@Query("update UserSettings s set s.defaultContentMode = :mode, s.selectedCategories = :categories, "
			+ "s.updatedAt = CURRENT_TIMESTAMP where s.userId = :userId")
	int updateSettings(@Param("userId") UUID userId, @Param("mode") String mode,
			@Param("categories") String[] categories);
}
