package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TimeLimiterContextTest {
    @Test
    void propagatesAttemptBudgetAndRestoresCaller() throws Exception {
        try (var limiter = new TimeLimiter(2)) {
            long remaining =
                    limiter.call(
                            () -> ResilienceContext.deadline().remainingNanos(),
                            new Deadline(Duration.ofSeconds(2), Ticker.SYSTEM),
                            Duration.ofMillis(100));
            assertTrue(remaining > 0 && remaining <= 100000000L);
            assertNull(ResilienceContext.deadline());
        }
    }
}
