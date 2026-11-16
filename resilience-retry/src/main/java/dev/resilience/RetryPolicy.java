package dev.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        Duration maxElapsed,
        boolean jitter,
        boolean exponential) {
    public RetryPolicy {
        Checks.range(maxAttempts, 10);
        Checks.duration(initialDelay, true);
        Checks.duration(maxDelay, true);
        Checks.duration(maxElapsed, false);
        if (initialDelay.compareTo(maxDelay) > 0)
            throw new IllegalArgumentException("Initial delay exceeds cap");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(
                3, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(2), true, true);
    }

    public long delayNanos(int retry) {
        long cap = maxDelay.toNanos(), n = initialDelay.toNanos();
        if (exponential) for (int i = 1; i < retry; i++) n = n >= cap / 2 ? cap : Math.min(cap, n * 2);
        return jitter && n > 0 ? ThreadLocalRandom.current().nextLong(n + 1) : n;
    }
}
