package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class ExecutorTest {
    PolicyRegistry registry;
    ResilienceExecutor executor;

    @BeforeEach
    void setup() {
        registry = new PolicyRegistry(4, Ticker.SYSTEM);
        var base = PolicyConfig.defaults();
        registry.update(
                "test",
                new PolicyConfig(
                        base.circuit(),
                        new RetryPolicy(3, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2), false, true),
                        1,
                        Duration.ZERO,
                        Duration.ofMillis(150),
                        Duration.ofSeconds(2)));
        executor = new ResilienceExecutor(registry, 4, 4, EventSink.NOOP);
    }

    @AfterEach
    void close() {
        executor.close();
    }

    @Test
    void callerDeadlineAndNullSafety() {
        var clock = new AtomicLong();
        var deadline = new Deadline(Duration.ofNanos(1), clock::get);
        clock.incrementAndGet();
        assertThrows(
                TimeoutException.class,
                () -> executor.execute("test", RetrySafety.UNSAFE, () -> "bad", null, deadline));
        assertThrows(NullPointerException.class, () -> executor.execute("test", null, () -> "bad"));
    }

    @Test
    void retrySafety() throws Exception {
        var n = new AtomicInteger();
        assertThrows(
                java.io.IOException.class,
                () ->
                        executor.execute(
                                "test",
                                RetrySafety.UNSAFE,
                                () -> {
                                    n.incrementAndGet();
                                    throw new java.io.IOException();
                                }));
        assertEquals(1, n.get());
        n.set(0);
        assertEquals(
                "ok",
                executor.execute(
                        "test",
                        RetrySafety.IDEMPOTENT,
                        () -> {
                            if (n.incrementAndGet() < 3) throw new java.io.IOException();
                            return "ok";
                        }));
        assertEquals(3, n.get());
    }

    @Test
    void exceptionsAndFallback() throws Exception {
        var failure = new IllegalArgumentException("domain");
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                executor.execute(
                                        "test",
                                        RetrySafety.IDEMPOTENT,
                                        () -> {
                                            throw failure;
                                        })));
        assertEquals(
                "stale",
                executor.execute(
                        "test",
                        RetrySafety.UNSAFE,
                        () -> {
                            throw new java.io.IOException();
                        },
                        new Fallback<String>() {
                            public boolean eligible(Exception e) {
                                return e instanceof java.io.IOException;
                            }

                            public String apply(Exception e) {
                                return "stale";
                            }
                        }));
        assertEquals(1, registry.get("test").circuit().snapshot().failures());
    }

    @Test
    void timeoutRetainsPermitUntilActualExit() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try {
            assertThrows(
                    TimeoutException.class,
                    () ->
                            executor.execute(
                                    "test",
                                    RetrySafety.UNSAFE,
                                    () -> {
                                        started.countDown();
                                        try {
                                            while (true) {
                                                try {
                                                    release.await();
                                                    break;
                                                } catch (InterruptedException ignored) {
                                                }
                                            }
                                            return "late";
                                        } finally {
                                            exited.countDown();
                                        }
                                    }));
            assertEquals(0, started.getCount());
            assertEquals(1, registry.get("test").bulkhead().active());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute("test", RetrySafety.UNSAFE, () -> "bad"));
        } finally {
            release.countDown();
        }
        assertTrue(exited.await(1, TimeUnit.SECONDS));
    }

    @Test
    void asyncCancellation() throws Exception {
        var started = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var future =
                executor.executeAsync(
                        "test",
                        RetrySafety.UNSAFE,
                        () -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                                return "bad";
                            } finally {
                                exited.countDown();
                            }
                        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
    }

    @Test
    void asyncResult() throws Exception {
        assertEquals(
                42, executor.executeAsync("test", RetrySafety.UNSAFE, () -> 42).get(1, TimeUnit.SECONDS));
    }

    @Test
    void telemetryFailureIsIsolated() throws Exception {
        try (var e =
                     new ResilienceExecutor(
                             registry,
                             2,
                             2,
                             (p, n, v) -> {
                                 throw new IllegalStateException();
                             })) {
            assertEquals("ok", e.execute("test", RetrySafety.UNSAFE, () -> "ok"));
        }
    }

    @Test
    void immutableUpdateAndBounds() {
        var old = registry.get("test");
        var next = registry.update("test", PolicyConfig.defaults());
        assertTrue(next.version() > old.version());
        assertNotSame(old.bulkhead(), next.bulkhead());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.update("request/user", PolicyConfig.defaults()));
    }

    @Test
    void deterministicBudget() throws Exception {
        var clock = new AtomicLong();
        var r = new PolicyRegistry(1, clock::get);
        var c = PolicyConfig.defaults();
        r.update(
                "test",
                new PolicyConfig(
                        c.circuit(),
                        new RetryPolicy(
                                3,
                                Duration.ofMillis(100),
                                Duration.ofMillis(100),
                                Duration.ofMillis(50),
                                false,
                                true),
                        1,
                        Duration.ZERO,
                        Duration.ofMillis(100),
                        Duration.ofMillis(200)));
        var n = new AtomicInteger();
        try (var e =
                     new ResilienceExecutor(
                             r, 4, 4, EventSink.NOOP, clock::get, clock::addAndGet, FailureClassifier.DEFAULT)) {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            e.execute(
                                    "test",
                                    RetrySafety.IDEMPOTENT,
                                    () -> {
                                        n.incrementAndGet();
                                        throw new java.io.IOException();
                                    }));
            assertEquals(1, n.get());
        }
    }

    @Test
    void shutdownRejects() {
        executor.close();
        assertThrows(
                RejectedExecutionException.class,
                () -> executor.execute("test", RetrySafety.UNSAFE, () -> "bad"));
    }
}
