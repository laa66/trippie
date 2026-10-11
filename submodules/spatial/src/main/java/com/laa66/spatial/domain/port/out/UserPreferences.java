package com.laa66.spatial.domain.port.out;

import java.util.UUID;

/**
 * Outbound port for the one user preference spatial is allowed to know about: whether this user
 * still requires physical proximity before a POI may be unlocked.
 *
 * <p><strong>One field, deliberately.</strong> The settings resource behind the adapter also
 * carries the selected categories and the default content mode; neither is exposed here and
 * spatial must not grow a reason to read them. Category filtering reaches spatial as an explicit
 * query parameter chosen by the client, which keeps the filter a request-scoped argument rather
 * than ambient user state this service fetches behind the caller's back.
 *
 * <p><strong>Read on the write path only — never on {@code nearby}.</strong> It is read as an unlock
 * row is created and its result persisted as {@code granted_by}, so no later reader asks again;
 * on the read path it would put an auth round trip in front of every map pan (rationale and the
 * rejected alternatives: M3 frozen decisions in {@code .claude/PLAN.md}).
 *
 * <p><strong>This method answers, and it answers strictly.</strong> It throws nothing that auth can
 * do to it — a timeout, a 5xx, a 401, an empty body or an unparseable one all yield {@code true} —
 * and it never returns a "don't know". Degradation is resolved by the adapter rather than at each
 * call site so that there is exactly one place it can be got wrong. It is closed rather than open
 * because the failure is not symmetric: answering "proximity not required" on a failed lookup mints
 * permanent {@code granted_by = 'EXEMPT'} rows for a user who never asked to be exempt, and there is
 * no re-lock to undo them. Degradation makes this application stricter, never looser. A bug inside
 * the adapter is not degradation and does propagate.
 */
public interface UserPreferences {

	boolean requireProximity(UUID userId);
}
