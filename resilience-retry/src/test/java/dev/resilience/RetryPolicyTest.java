package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class RetryPolicyTest {
    @Test
    void exponentialAndCap() {
        var p =
                new RetryPolicy(
                        3, Duration.ofNanos(10), Duration.ofNanos(25), Duration.ofSeconds(1), false, true);
        assertEquals(10, p.delayNanos(1));
        assertEquals(20, p.delayNanos(2));
        assertEquals(25, p.delayNanos(3));
        assertEquals(25, p.delayNanos(100));
    }

    @Test
    void jitterBounds() {
        var p = RetryPolicy.defaults();
        for (int i = 0; i < 1000; i++) {
            long n = p.delayNanos(10);
            assertTrue(n >= 0 && n <= p.maxDelay().toNanos());
        }
    }

    @Test
    void rejectsInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(0, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), false, false));
        assertThrows(IllegalArgumentException.class, () -> Checks.duration(Duration.ofDays(2), false));
    }
}
