package com.laa66.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code user_settings} JPA entity (1:1 with {@code app_user}, {@code user_id} is both PK and FK).
 * {@code selected_categories} maps the Postgres {@code text[]} column via {@link SqlTypes#ARRAY}.
 * {@code require_proximity} and {@code updated_at} are mapped {@code insertable = false} so the DB default fills them
 * on the insert done by {@code create()}; they are only ever written by the JPQL bulk update on
 * {@link UserSettingsJpaRepository}, which needs the attributes to reference {@code s.requireProximity} and
 * {@code s.updatedAt}.
 */
@Entity
@Table(name = "user_settings")
class UserSettings {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "default_content_mode")
	private String defaultContentMode;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "selected_categories")
	private String[] selectedCategories;

	@Column(name = "require_proximity", insertable = false)
	private boolean requireProximity = true;

	@Column(name = "updated_at", insertable = false)
	private Instant updatedAt;

	protected UserSettings() {
	}

	UserSettings(UUID userId, String defaultContentMode, String[] selectedCategories) {
		this.userId = userId;
		this.defaultContentMode = defaultContentMode;
		this.selectedCategories = selectedCategories;
	}

	String getDefaultContentMode() {
		return defaultContentMode;
	}

	String[] getSelectedCategories() {
		return selectedCategories;
	}

	boolean isRequireProximity() {
		return requireProximity;
	}
}
