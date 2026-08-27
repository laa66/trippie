-- Auth owns auth_db via Flyway. infra/init only issues the cluster-level CREATE DATABASE;
-- every table and extension for this DB lives here so the service fully owns its schema.
--
-- citext (case-insensitive text) is a TRUSTED extension since PG13: a non-superuser with
-- CREATE on the database can enable it, so keeping it in the migration is safe and keeps
-- email-casing an auth concern instead of leaking it into the superuser-only init script.
-- It makes `=` and the UNIQUE constraint case-insensitive at the type level, so no separate
-- lower(email) functional index is needed and every read path gets the folding for free.
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE app_user (
    id             UUID        PRIMARY KEY,
    email          CITEXT      NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    email_verified BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 1:1 with app_user (user_id is both PK and FK). ON DELETE CASCADE ties a settings row's
-- lifetime to its user. Signup seeds mode=BOTH + all 8 categories at the application layer
-- (M2-03), so the columns are NOT NULL but carry no DB default — the seed is a business rule
-- owned by one place, not duplicated as a default that could mask a missing app-side write.
CREATE TABLE user_settings (
    user_id              UUID        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    default_content_mode TEXT        NOT NULL,
    selected_categories  TEXT[]      NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT user_settings_content_mode_check
        CHECK (default_content_mode IN ('TEXT', 'AUDIO', 'BOTH')),

    -- Same "DB is the enforcement point for the category contract" philosophy as M1's
    -- location_point CHECK: <@ asserts every element of selected_categories is one of the
    -- eight shared slugs, catching drift with no shared codegen artifact.
    CONSTRAINT user_settings_categories_check
        CHECK (selected_categories <@ ARRAY[
            'public_art',
            'monuments',
            'heritage',
            'sacred',
            'museums',
            'viewpoints',
            'architecture',
            'attractions'
        ]::text[])
);
