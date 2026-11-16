package dev.resilience;

/**
 * IDEMPOTENCY_KEY requires a verified server-side deduplication contract, not just a header.
 */
public enum RetrySafety {
    UNSAFE,
    IDEMPOTENT,
    IDEMPOTENCY_KEY
}
