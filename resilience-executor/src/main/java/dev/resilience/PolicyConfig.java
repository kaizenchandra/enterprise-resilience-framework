package dev.resilience;

import java.time.Duration;

public record PolicyConfig(
        CircuitConfig circuit,
        RetryPolicy retry,
        int concurrency,
        Duration bulkheadWait,
        Duration perAttempt,
        Duration overall) {
    public PolicyConfig {
        java.util.Objects.requireNonNull(circuit);
        java.util.Objects.requireNonNull(retry);
        Checks.range(concurrency, 10000);
        Checks.duration(bulkheadWait, true);
        Checks.duration(perAttempt, false);
        Checks.duration(overall, false);
        if (perAttempt.compareTo(overall) > 0)
            throw new IllegalArgumentException("Attempt timeout exceeds overall");
    }

    public static PolicyConfig defaults() {
        return new PolicyConfig(
                CircuitConfig.defaults(),
                RetryPolicy.defaults(),
                50,
                Duration.ZERO,
                Duration.ofMillis(500),
                Duration.ofSeconds(2));
    }
}
