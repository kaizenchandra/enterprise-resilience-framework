package dev.resilience;

import java.util.*;

/**
 * Local token buckets. Fleet protection requires RedisRetryBudget or another shared implementation.
 */
public final class LocalRetryBudget implements RetryBudget {
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final int capacity;
    private final double perNano;
    private final Ticker ticker;
    public LocalRetryBudget(
            Set<String> policies, int capacity, double refillPerSecond, Ticker ticker) {
        Checks.range(capacity, 1000000);
        if (!Double.isFinite(refillPerSecond) || refillPerSecond <= 0 || refillPerSecond > 1000000)
            throw new IllegalArgumentException("Invalid refill rate");
        if (policies.size() > 1000) throw new IllegalArgumentException("Too many policies");
        this.capacity = capacity;
        this.perNano = refillPerSecond / 1e9;
        this.ticker = ticker;
        policies.forEach(
                p -> {
                    Checks.name(p);
                    buckets.put(p, new Bucket(capacity, ticker.read()));
                });
    }

    public synchronized Decision acquire(String policy, Deadline deadline)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var b = buckets.get(policy);
        if (b == null || deadline.remainingNanos() == 0) return Decision.UNAVAILABLE;
        long now = ticker.read();
        b.tokens = Math.min(capacity, b.tokens + Math.max(0, now - b.last) * perNano);
        b.last = now;
        if (b.tokens < 1) return Decision.EXHAUSTED;
        b.tokens--;
        return Decision.ALLOWED;
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(int capacity, long now) {
            tokens = capacity;
            last = now;
        }
    }
}
