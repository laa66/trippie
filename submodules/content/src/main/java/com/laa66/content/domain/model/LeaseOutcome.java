package com.laa66.content.domain.model;

/**
 * What the one atomic lease statement decided. Every value is reached by the same statement in the
 * same round trip, so the caller never has to combine two reads into a decision — and this is the
 * decision that gates a paid provider call, so collapsing two of these values is a second payment
 * for the same POI, not a cosmetic loss.
 */
public enum LeaseOutcome {

	/**
	 * The lease is ours: either the row was created by this statement, or a {@code PROCESSING} row
	 * whose lease had expired was taken over. {@code attempts} moved either way.
	 */
	GRANTED,

	/**
	 * The lease is ours and the row was {@code FAILED} with retry budget left — the same write as
	 * {@link #GRANTED}, reported apart because a retry is the one lease that re-enters a row the
	 * system already gave up on once. Callers that only decide whether to publish treat the two
	 * alike.
	 */
	RETRY_GRANTED,

	/**
	 * Another attempt holds a live lease, so this caller must not publish anything. Also the answer
	 * when a concurrent statement created the row a microsecond earlier: whoever won it holds a
	 * fresh lease by construction.
	 */
	HELD_BY_ANOTHER_ATTEMPT,

	/** The content is already there. Nothing was written and nothing should be generated. */
	ALREADY_COMPLETED,

	/**
	 * The row has spent {@code MAX_ATTEMPTS_PER_ROW} inside the attempt window: no lease, no
	 * publication, and no new attempt until the window passes. This is the value that keeps a
	 * client's "Retry" from becoming a loop over a paid API. The row is {@code FAILED} once the
	 * statement is done, and its class travels with the answer.
	 */
	ATTEMPTS_EXHAUSTED
}
