package com.laa66.content.domain.model;

/**
 * What a read of one {@code poi_content} row serves: the projection the gated read (M4-06) and the
 * worker's pre-check (M4-11) consume, and nothing else.
 *
 * <p>Deliberately not the whole row. {@code failed_detail} carries the raw provider response and
 * must never leave the service, so it is not modelled. {@code started_at} and
 * {@code last_attempt_at} are the lease statement's own working state — the only thing that may
 * read them is the predicate inside it. {@code attempts} is already answered, for every consumer
 * that has a decision to make, by {@link LeaseOutcome#ATTEMPTS_EXHAUSTED}. Anything that needs a
 * column from this list is a new consumer and a new decision, not a getter.
 *
 * <p>{@code text} is non-null exactly when {@code status} is {@link ContentStatus#COMPLETED} and
 * {@code failedClass} is non-null whenever it is {@link ContentStatus#FAILED}; both are table
 * CHECKs ({@code poi_content_state_check}), not conventions. The one loose case stays loose here
 * too: a {@code FAILED} or {@code PROCESSING} row may carry servable {@code text} from an earlier
 * success.
 */
public record PoiContent(ContentStatus status, String text, FailedClass failedClass) {
}
