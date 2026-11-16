package dev.resilience;

import java.util.*;

/**
 * Updates replace immutable snapshots. Old in-flight calls retain their resources and circuit
 * state.
 */
public final class PolicyRegistry {
    private final Map<String, Policy> entries = new HashMap<>();
    private final int limit;
    private final Ticker ticker;
    private long version;
    public PolicyRegistry(int limit, Ticker ticker) {
        this.limit = Checks.range(limit, 1000);
        this.ticker = ticker;
    }

    public synchronized Policy update(String name, PolicyConfig config) {
        Checks.name(name);
        Objects.requireNonNull(config);
        if (!entries.containsKey(name) && entries.size() >= limit)
            throw new IllegalStateException("Policy registry full");
        Policy p =
                new Policy(
                        ++version,
                        config,
                        new CircuitBreaker(config.circuit(), ticker),
                        new Bulkhead(config.concurrency(), config.bulkheadWait(), true));
        entries.put(name, p);
        return p;
    }

    public synchronized Policy get(String name) {
        Policy p = entries.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown policy: " + name);
        return p;
    }

    public synchronized Map<String, Policy> snapshot() {
        return Map.copyOf(entries);
    }

    public record Policy(
            long version, PolicyConfig config, CircuitBreaker circuit, Bulkhead bulkhead) {
    }
}
