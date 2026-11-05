package com.laa66.content.domain.model;

import java.time.Instant;

/**
 * What the lease statement decided, plus the token that proves which lease it was.
 *
 * <p>{@code leasedAt} is the {@code started_at} the statement wrote, and it is non-null exactly
 * when the outcome granted a lease. It exists because "I hold the lease" is not a fact a worker can
 * assert later from memory: its lease may have expired and been taken over while it was still
 * working, and a result written without the token would then be written on top of somebody else's
 * attempt. Every write-back carries it, and the write applies only if the row still bears it.
 *
 * <p><strong>The token has to travel without losing precision.</strong> {@code timestamptz} resolves
 * microseconds and the fence compares for equality, so a token rounded to milliseconds anywhere on
 * its way matches nothing, and nothing says so. Measured: {@code failGeneration} with a millisecond
 * token is a no-op, the row stays {@code PROCESSING}, it is taken over after five minutes and the
 * POST publishes again, which is a second payment for the same POI. The asymmetry between the two
 * write-backs hides it: a completion with the same token still writes through its
 * {@code text IS NULL} arm, so a happy-path E2E stays green and only failure reports and regeneration
 * results disappear.
 *
 * <p><strong>Do not repair that by truncating both sides to milliseconds.</strong> Consecutive
 * takeover tokens were measured 2-3 ms apart in a test and are five minutes apart in production, so
 * the coarser token buys nothing; the fix belongs to whichever hop loses the precision.
 *
 * <p>{@code failedClass} is the row's class as the same statement left it, and it is non-null
 * exactly when the outcome is {@link LeaseOutcome#ATTEMPTS_EXHAUSTED}: that answer is a
 * {@code FAILED} row, and the client is told why in the same shape a read of the row would give.
 */
public record GenerationLease(LeaseOutcome outcome, Instant leasedAt, FailedClass failedClass) {
}
