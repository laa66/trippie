-- Name points at the safe default: TRUE means the user must physically approach a POI to unlock it.
ALTER TABLE user_settings ADD COLUMN require_proximity BOOLEAN NOT NULL DEFAULT TRUE;
