package dev.resilience;

/**
 * Budget applies only to additional attempts, not initial calls. Implementations fail closed.
 */
public interface RetryBudget {
    RetryBudget UNLIMITED = (p, d) -> Decision.ALLOWED;

    Decision acquire(String policy, Deadline deadline) throws InterruptedException;

    enum Decision {
        ALLOWED,
        EXHAUSTED,
        UNAVAILABLE
    }
}
