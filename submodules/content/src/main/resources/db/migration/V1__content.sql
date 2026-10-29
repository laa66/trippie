-- Generated POI content, one row per (POI, mode, lang). No FK to location_point: that table lives
-- in spatial_db, a different database.
--
-- osm_id is TEXT because the real id carries its element type and a slash ('node/42').
-- lang is a key dimension from day one; content is Polish today, so it is always 'pl'.
--
-- started_at and last_attempt_at are separate on purpose and answer different questions:
-- started_at is the lease (is a PROCESSING row still owned by a live worker?), last_attempt_at is
-- the quota window (when was this row last attempted?). Merging them would make one of the two
-- questions unanswerable.
--
-- As with spatial's CHECKs, the DB is the enforcement point for value sets that have no shared
-- codegen artifact: mode must match the TEXT/AUDIO/BOTH content modes held in auth's settings.
CREATE TABLE poi_content (
    osm_id          TEXT        NOT NULL,
    mode            TEXT        NOT NULL,
    lang            TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    text            TEXT,
    prompt_version  TEXT,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    failed_class    TEXT,
    failed_detail   TEXT,
    -- Lease: which attempt owns a PROCESSING row, and since when. The takeover predicate in M4-03
    -- reads "started_at older than GENERATION_LEASE_TIMEOUT", and SQL makes that NULL -> false on a
    -- NULL column, so a PROCESSING row with no started_at is a row nobody can ever take over --
    -- silent, with no error and no FAILED row, exactly like the last_attempt_at hole below.
    -- Measured before the fix: that INSERT was accepted.
    --
    -- Closed in the state CHECK (the PROCESSING arm requires it) rather than with
    -- `NOT NULL DEFAULT now()`, deliberately: this column answers a question only a PROCESSING row
    -- has, namely who holds the lease. NOT NULL would force a timestamp onto every COMPLETED and
    -- FAILED row too, which conflates "lease held" with "lease over / never leased". A bare
    -- DEFAULT now() would not close anything either -- `UPDATE ... SET started_at = NULL` stays
    -- legal under a default.
    started_at      TIMESTAMPTZ,

    -- Quota window, NOT NULL with a default, because the attempt-counter reset in M4-03 is written
    -- as "last_attempt_at older than 24 h" and SQL makes that predicate NULL -> false on a NULL
    -- column. The reachable state it closes: a lease is taken, attempts climbs to
    -- MAX_ATTEMPTS_PER_ROW while stuck PROCESSING rows are taken over, the message is then lost or
    -- dead-lettered and the worker never reports back, so last_attempt_at stays NULL and the row
    -- is permanently ungeneratable -- with no error, no FAILED row and nothing to detect it by.
    -- DEFAULT now() closes it at the only moment a row comes into existence, which is the lease.
    --
    -- Deliberate consequence: an "attempt" is counted from the LEASE, not from the provider call.
    -- A lease whose worker dies before Gemini is ever called still burns an attempt. That is the
    -- conservative direction -- counting from the provider call is exactly what would let a worker
    -- that never reports keep the row's budget alive forever. M4-03's lease statement should keep
    -- this column moving (set it alongside attempts) so the 24 h window tracks real activity; the
    -- default only guarantees it is never NULL.
    last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (osm_id, mode, lang),

    CONSTRAINT poi_content_mode_check CHECK (mode IN ('TEXT', 'AUDIO', 'BOTH')),
    CONSTRAINT poi_content_status_check CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),

    -- NULL passes this CHECK, and that is required, not incidental: `NULL IN (...)` evaluates to
    -- NULL and a CHECK only rejects FALSE, so an unset failed_class is accepted. Every non-FAILED
    -- row needs that. Pinned as an explicit criterion in PoiContentMigrationIntTest rather than
    -- being true only as a side effect.
    CONSTRAINT poi_content_failed_class_check CHECK (failed_class IN ('TRANSIENT', 'PERMANENT', 'QUOTA')),

    -- lang is in the PK but had no shape rule, so 'pl' and 'PL' were two rows: the same POI paid
    -- for twice and a cache split in half. Enforced here for the same reason as mode -- the DB is
    -- the only enforcement point a Java service, a Go loader and a worker all share.
    --
    -- Shape, not enum: lowercase ISO 639-1. `lang IN ('pl')` would pin today's truth (lang is a
    -- server-side constant, M4 input decision 6) but makes a second language a migration, and it
    -- also makes the lang dimension of the PK untestable -- proving lang is part of the key needs
    -- a second legal value. The hazard worth making impossible today is the case fold, not the
    -- value set, which M5 is expected to widen.
    CONSTRAINT poi_content_lang_check CHECK (lang = lower(lang) AND length(lang) = 2),

    -- Ties status to the columns each status promises. Without it these were all representable:
    -- FAILED with no failed_class (M4-06 returns failedClass, M4-14 builds UX on it), COMPLETED
    -- with no text (no branch for it in M4-12's status machine) and COMPLETED with no completed_at
    -- (M4-03's write idempotency rests on it).
    --
    -- PROCESSING requires started_at (see that column) and forbids completed_at (a row carrying an
    -- earlier completed_at would read as "already done" to an idempotency check) and failed_class
    -- (a stale class would be served by M4-06 while generation is in fact running). Cost, accepted:
    -- a lease that takes over a FAILED row -- or, in a future regeneration, a COMPLETED one -- must
    -- set started_at and clear the other two in the same UPDATE. A forgotten SET is then a loud
    -- constraint violation on the lease, not a quietly wrong answer.
    --
    -- Left loose on purpose:
    --   * PROCESSING may keep `text`, so a row can be regenerated while the previous text is still
    --     servable.
    --   * FAILED requires only failed_class and may keep `text` from an earlier success, so a later
    --     failure does not destroy servable content. It does NOT arrive carrying completed_at: the
    --     row passed through the PROCESSING arm on its way here, and that arm had to clear it. The
    --     arm stays loose about completed_at rather than pinning a state nothing can produce.
    --   * COMPLETED and FAILED may leave started_at unset: the lease is over, nobody takes them
    --     over, and the takeover predicate only ever runs against PROCESSING rows.
    --   * COMPLETED additionally requires failed_class IS NULL. It is free given the PROCESSING arm
    --     (the only way into COMPLETED is through a lease, which already cleared it) and it stops
    --     M4-06 from returning a failedClass next to finished text.
    CONSTRAINT poi_content_state_check CHECK (
        (status = 'PROCESSING' AND started_at IS NOT NULL
                               AND completed_at IS NULL AND failed_class IS NULL)
     OR (status = 'COMPLETED'  AND text IS NOT NULL AND completed_at IS NOT NULL
                               AND failed_class IS NULL)
     OR (status = 'FAILED'     AND failed_class IS NOT NULL)
    )
);
