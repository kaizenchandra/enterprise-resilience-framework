package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class RetryBudgetTest {
    @Test
    void refillAndBounds() throws Exception {
        var clock = new AtomicLong();
        var b = new LocalRetryBudget(Set.of("payment"), 2, 1, clock::get);
        var d = new Deadline(Duration.ofSeconds(10), clock::get);
        assertEquals(RetryBudget.Decision.ALLOWED, b.acquire("payment", d));
        assertEquals(RetryBudget.Decision.ALLOWED, b.acquire("payment", d));
        assertEquals(RetryBudget.Decision.EXHAUSTED, b.acquire("payment", d));
        clock.addAndGet(1000000000L);
        assertEquals(RetryBudget.Decision.ALLOWED, b.acquire("payment", d));
        assertEquals(RetryBudget.Decision.UNAVAILABLE, b.acquire("user-key", d));
    }

    @Test
    void concurrentTokensCannotBeOverspent() throws Exception {
        var b = new LocalRetryBudget(Set.of("p"), 25, .00001, () -> 0);
        var d = new Deadline(Duration.ofSeconds(10), () -> 0);
        var allowed = new AtomicInteger();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 1000; i++)
                tasks.add(
                        threads.submit(
                                () -> {
                                    if (b.acquire("p", d) == RetryBudget.Decision.ALLOWED) allowed.incrementAndGet();
                                    return null;
                                }));
            for (var f : tasks) f.get();
        }
        assertEquals(25, allowed.get());
    }

    @Test
    void invalidParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalRetryBudget(Set.of("p"), 1, Double.NaN, Ticker.SYSTEM));
    }
}
