package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

@Tag("soak")
class ReliabilitySoakTest {
    @Test
    void mixedFailuresCancellationAndRecovery() throws Exception {
        int seconds = Integer.getInteger("soak.seconds", 60);
        assertTrue(seconds >= 5 && seconds <= 86400);
        var registry = new PolicyRegistry(1, Ticker.SYSTEM);
        var c = PolicyConfig.defaults();
        registry.update(
                "soak",
                new PolicyConfig(
                        new CircuitConfig(100, 20, 60, 100, Duration.ofMillis(100), Duration.ofMillis(20), 3),
                        new RetryPolicy(
                                3, Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(300), true, true),
                        4,
                        Duration.ZERO,
                        Duration.ofMillis(100),
                        Duration.ofMillis(300)));
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var calls = new AtomicLong();
        var operations = new AtomicLong();
        var failures = new AtomicLong();
        var cancelled = new AtomicLong();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        var budget = new LocalRetryBudget(Set.of("soak"), 50, 20, Ticker.SYSTEM);
        try (var executor =
                     new ResilienceExecutor(
                             registry,
                             8,
                             8,
                             EventSink.NOOP,
                             Ticker.SYSTEM,
                             Sleeper.SYSTEM,
                             FailureClassifier.DEFAULT,
                             budget,
                             TaskContext.FRAMEWORK);
             var clients = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int index = 0; index < 8; index++) {
                final int seed = index;
                tasks.add(
                        clients.submit(
                                () -> {
                                    var random = new Random(seed);
                                    while (System.nanoTime() < end) {
                                        operations.incrementAndGet();
                                        try {
                                            if (random.nextInt(30) == 0) {
                                                var started = new CountDownLatch(1);
                                                var f =
                                                        executor.executeAsync(
                                                                "soak",
                                                                RetrySafety.UNSAFE,
                                                                () -> {
                                                                    started.countDown();
                                                                    Thread.sleep(10);
                                                                    return "cancel";
                                                                });
                                                f.cancel(true);
                                                cancelled.incrementAndGet();
                                            } else
                                                executor.execute(
                                                        "soak",
                                                        RetrySafety.IDEMPOTENT,
                                                        () -> {
                                                            int current = active.incrementAndGet();
                                                            maximum.accumulateAndGet(current, Math::max);
                                                            try {
                                                                long n = calls.incrementAndGet();
                                                                if (n % 7 == 0)
                                                                    throw new java.io.IOException("injected");
                                                                if (n % 13 == 0)
                                                                    throw new IllegalArgumentException("business");
                                                                Thread.sleep(n % 11 == 0 ? 4 : 1);
                                                                return "ok";
                                                            } finally {
                                                                active.decrementAndGet();
                                                            }
                                                        });
                                        } catch (java.io.IOException
                                                 | IllegalArgumentException
                                                 | CircuitOpenException
                                                 | RejectedExecutionException
                                                 | TimeoutException expected) {
                                            failures.incrementAndGet();
                                        }
                                        Thread.sleep(1);
                                    }
                                    return null;
                                }));
            }
            for (var task : tasks) task.get(seconds + 10, TimeUnit.SECONDS);
            for (int i = 0;
                 i < 100 && (active.get() != 0 || registry.get("soak").bulkhead().active() != 0);
                 i++)
                Thread.sleep(5);
            assertEquals(0, active.get());
            assertEquals(0, registry.get("soak").bulkhead().active());
            assertTrue(maximum.get() <= 4);
            assertTrue(calls.get() > 100);
            assertTrue(failures.get() > 0);
            assertTrue(cancelled.get() > 0);
            System.out.printf(
                    "SOAK seconds=%d operations=%d downstream=%d expectedFailures=%d cancelled=%d"
                            + " maxPhysicalConcurrency=%d%n",
                    seconds, operations.get(), calls.get(), failures.get(), cancelled.get(), maximum.get());
        }
    }
}
