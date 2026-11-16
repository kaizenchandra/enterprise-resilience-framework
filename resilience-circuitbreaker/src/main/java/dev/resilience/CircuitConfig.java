package dev.resilience;

import java.time.Duration;

public record CircuitConfig(
        int windowSize,
        int minimumCalls,
        double failureRate,
        double slowRate,
        Duration slowDuration,
        Duration openDuration,
        int probes) {
    public CircuitConfig {
        Checks.range(windowSize, 100000);
        Checks.range(minimumCalls, windowSize);
        Checks.range(probes, 1000);
        if (!Double.isFinite(failureRate)
                || failureRate <= 0
                || failureRate > 100
                || !Double.isFinite(slowRate)
                || slowRate <= 0
                || slowRate > 100) throw new IllegalArgumentException("Invalid threshold");
        Checks.duration(slowDuration, false);
        Checks.duration(openDuration, false);
    }

    public static CircuitConfig defaults() {
        return new CircuitConfig(100, 20, 50, 100, Duration.ofMillis(400), Duration.ofSeconds(30), 5);
    }
}
