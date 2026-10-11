-- Server-side unlock facts: one row per (user, POI) the server has granted. This is spatial's
-- first write path — unlocking is a geometric predicate (ST_DWithin) and can only be enforced
-- where the geometry lives, so the fact is recorded here rather than in auth_db.
--
-- user_id is the opaque UUID the gateway forwards as X-User-Id. Identities live in auth_db, in
-- another database, so there is deliberately no FK and spatial never validates the subject.
--
-- osm_id REFERENCES location_point is what makes a row a fact about a POI that exists: it is the
-- schema-level backstop behind whatever the write query checks. Safe against the loader, which
-- only upserts (ON CONFLICT (osm_id) DO UPDATE) and never deletes, so the CASCADE is a
-- correctness guarantee for a path production does not take.
--
-- PK (user_id, osm_id) carries both access patterns on its own: the idempotent write conflicts on
-- it, and the batch read filters `user_id = ? AND osm_id = ANY(?)` on its leading column. No
-- second index — nothing queries osm_id first except the CASCADE above.
--
-- granted_by records WHY the row exists: measured proximity, or the user's "do not require
-- proximity" setting. There is no re-lock, the distinction cannot be reconstructed afterwards,
-- and the content service is its consumer, so it is captured at grant time.
CREATE TABLE poi_unlock (
    user_id     UUID        NOT NULL,
    osm_id      TEXT        NOT NULL REFERENCES location_point(osm_id) ON DELETE CASCADE,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  TEXT        NOT NULL,
    PRIMARY KEY (user_id, osm_id),

    -- As with location_point.category, the DB is the enforcement point for a contract that has no
    -- shared codegen artifact: these two values and the UnlockSource enum must stay identical.
    CONSTRAINT poi_unlock_granted_by_check CHECK (granted_by IN ('PROXIMITY', 'EXEMPT'))
);
