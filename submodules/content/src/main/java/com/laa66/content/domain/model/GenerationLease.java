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
 */
public record GenerationLease(LeaseOutcome outcome, Instant leasedAt) {
}
