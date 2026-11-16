package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class PropagationAndBudgetTest {
    PolicyRegistry registry = new PolicyRegistry(1, Ticker.SYSTEM);

    @BeforeEach
    void configure() {
        var c = PolicyConfig.defaults();
        registry.update(
                "p",
                new PolicyConfig(
                        c.circuit(),
                        new RetryPolicy(3, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2), false, true),
                        4,
                        Duration.ZERO,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2)));
    }

    ResilienceExecutor executor(RetryBudget budget) {
        return new ResilienceExecutor(
                registry,
                8,
                8,
                EventSink.NOOP,
                Ticker.SYSTEM,
                Sleeper.SYSTEM,
                FailureClassifier.DEFAULT,
                budget,
                TaskContext.FRAMEWORK);
    }

    @Test
    void arbitraryInheritableThreadLocalsAreNotCopied() throws Exception {
        var tenant = new InheritableThreadLocal<String>();
        tenant.set("caller-sensitive-context");
        try (var e = executor(RetryBudget.UNLIMITED)) {
            assertNull(e.executeAsync("p", RetrySafety.UNSAFE, tenant::get).get(1, TimeUnit.SECONDS));
            assertEquals("caller-sensitive-context", tenant.get());
        } finally {
            tenant.remove();
        }
    }

    @Test
    void nonCooperativeBudgetCannotConsumeInitialCallWorkers() throws Exception {
        var release = new CountDownLatch(1);
        RetryBudget hung =
                (p, d) -> {
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException ignored) {
                        }
                    }
                    return RetryBudget.Decision.ALLOWED;
                };
        try (var e = executor(hung)) {
            var failure = new java.io.IOException("original");
            long start = System.nanoTime();
            assertSame(
                    failure,
                    assertThrows(
                            java.io.IOException.class,
                            () ->
                                    e.execute(
                                            "p",
                                            RetrySafety.IDEMPOTENT,
                                            () -> {
                                                throw failure;
                                            })));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertEquals("initial", e.execute("p", RetrySafety.UNSAFE, () -> "initial"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void onlyRetriesSpendBudgetAndErrorIsPreserved() throws Exception {
        var n = new AtomicInteger();
        var spending = new AtomicInteger();
        RetryBudget b =
                (p, d) -> {
                    spending.incrementAndGet();
                    return RetryBudget.Decision.EXHAUSTED;
                };
        try (var e = executor(b)) {
            assertEquals("ok", e.execute("p", RetrySafety.IDEMPOTENT, () -> "ok"));
            assertEquals(0, spending.get());
            var failure = new java.io.IOException("down");
            assertSame(
                    failure,
                    assertThrows(
                            java.io.IOException.class,
                            () ->
                                    e.execute(
                                            "p",
                                            RetrySafety.IDEMPOTENT,
                                            () -> {
                                                n.incrementAndGet();
                                                throw failure;
                                            })));
            assertEquals(1, n.get());
            assertEquals(1, spending.get());
        }
    }

    @Test
    void unsafeNeverConsultsBudget() {
        var spends = new AtomicInteger();
        try (var e =
                     executor(
                             (p, d) -> {
                                 spends.incrementAndGet();
                                 return RetryBudget.Decision.ALLOWED;
                             })) {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            e.execute(
                                    "p",
                                    RetrySafety.UNSAFE,
                                    () -> {
                                        throw new java.io.IOException();
                                    }));
            assertEquals(0, spends.get());
        }
    }

    @Test
    void automaticAsyncDeadlineAndNestedScope() throws Exception {
        var parent = new Deadline(Duration.ofMillis(400), Ticker.SYSTEM);
        try (var scope = ResilienceContext.withDeadline(parent);
             var e = executor(RetryBudget.UNLIMITED)) {
            assertTrue(
                    e.executeAsync(
                                    "p",
                                    RetrySafety.UNSAFE,
                                    () -> {
                                        var inherited = ResilienceContext.deadline();
                                        assertNotNull(inherited);
                                        assertTrue(inherited.remainingNanos() <= parent.remainingNanos() + 1000000);
                                        return inherited.remainingNanos() < 400000000L;
                                    })
                            .get(1, TimeUnit.SECONDS));
            assertSame(parent, ResilienceContext.deadline());
        }
        assertNull(ResilienceContext.deadline());
    }

    @Test
    void childNeverExtendsParent() {
        var time = new AtomicLong();
        var parent = new Deadline(Duration.ofNanos(10), time::get);
        var child = parent.capped(Duration.ofSeconds(1));
        time.set(10);
        assertEquals(0, child.remainingNanos());
        assertThrows(TimeoutException.class, child::check);
    }

    @Test
    void failedBudgetSuppressesRetryWithoutOpeningCircuit() {
        try (var e = executor((p, d) -> RetryBudget.Decision.UNAVAILABLE)) {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            e.execute(
                                    "p",
                                    RetrySafety.IDEMPOTENT,
                                    () -> {
                                        throw new java.io.IOException();
                                    }));
            assertEquals(1, registry.get("p").circuit().snapshot().failures());
        }
    }
}
