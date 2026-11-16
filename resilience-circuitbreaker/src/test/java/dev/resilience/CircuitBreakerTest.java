package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class CircuitBreakerTest {
    AtomicLong clock = new AtomicLong();
    CircuitConfig config =
            new CircuitConfig(4, 4, 50, 100, Duration.ofSeconds(1), Duration.ofSeconds(2), 2);
    CircuitBreaker cb = new CircuitBreaker(config, clock::get);

    void record(boolean failure) {
        cb.acquire()
                .complete(failure ? FailureClassifier.Outcome.FAILURE : FailureClassifier.Outcome.SUCCESS);
    }

    void open() {
        record(true);
        record(true);
        record(false);
        record(false);
        assertEquals(CircuitBreaker.State.OPEN, cb.snapshot().state());
    }

    @Test
    void ignoredProbeReturnsSlot() {
        open();
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        var ignored = cb.acquire();
        var success = cb.acquire();
        ignored.complete(FailureClassifier.Outcome.IGNORE);
        var replacement = cb.acquire();
        assertThrows(CircuitOpenException.class, cb::acquire);
        success.complete(FailureClassifier.Outcome.SUCCESS);
        replacement.complete(FailureClassifier.Outcome.SUCCESS);
        assertEquals(CircuitBreaker.State.CLOSED, cb.snapshot().state());
    }

    @Test
    void minimumAndWindow() {
        record(true);
        record(true);
        record(false);
        assertEquals(CircuitBreaker.State.CLOSED, cb.snapshot().state());
        record(false);
        assertEquals(CircuitBreaker.State.OPEN, cb.snapshot().state());
        assertThrows(CircuitOpenException.class, cb::acquire);
    }

    @Test
    void staleResultsAndRecovery() {
        open();
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        var old = cb.acquire();
        var other = cb.acquire();
        assertThrows(CircuitOpenException.class, cb::acquire);
        other.complete(FailureClassifier.Outcome.FAILURE);
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        var a = cb.acquire();
        var b = cb.acquire();
        a.complete(FailureClassifier.Outcome.SUCCESS);
        b.complete(FailureClassifier.Outcome.SUCCESS);
        old.complete(FailureClassifier.Outcome.FAILURE);
        assertEquals(CircuitBreaker.State.CLOSED, cb.snapshot().state());
    }

    @Test
    void ignoreAndExactlyOnce() {
        var t = cb.acquire();
        t.complete(FailureClassifier.Outcome.IGNORE);
        t.complete(FailureClassifier.Outcome.FAILURE);
        assertEquals(0, cb.snapshot().calls());
        assertEquals(1, cb.snapshot().ignored());
    }

    @Test
    void slowCalls() {
        for (int i = 0; i < 4; i++) {
            var t = cb.acquire();
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            t.complete(FailureClassifier.Outcome.SUCCESS);
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.snapshot().state());
    }

    @Test
    void halfOpenConcurrentAdmission() throws Exception {
        open();
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        var admitted = new ConcurrentLinkedQueue<CircuitBreaker.Ticket>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var fs = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++)
                fs.add(
                        threads.submit(
                                () -> {
                                    start.await();
                                    try {
                                        admitted.add(cb.acquire());
                                    } catch (CircuitOpenException expected) {
                                    }
                                    return null;
                                }));
            start.countDown();
            for (var f : fs) f.get();
        }
        assertEquals(2, admitted.size());
        admitted.forEach(t -> t.complete(FailureClassifier.Outcome.SUCCESS));
        assertEquals(CircuitBreaker.State.CLOSED, cb.snapshot().state());
    }

    @Test
    void ringUnderPlatformAndVirtualConcurrency() throws Exception {
        for (boolean virtual : new boolean[]{false, true}) {
            cb =
                    new CircuitBreaker(
                            new CircuitConfig(
                                    10000, 10000, 100, 100, Duration.ofSeconds(1), Duration.ofSeconds(1), 2),
                            clock::get);
            try (var pool =
                         virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(8)) {
                var fs = new ArrayList<Future<?>>();
                for (int i = 0; i < 5000; i++) fs.add(pool.submit(() -> record(false)));
                for (var f : fs) f.get();
            }
            assertEquals(5000, cb.snapshot().calls());
            assertEquals(0, cb.snapshot().failures());
        }
    }

    @Test
    void administrativeStates() {
        cb.forceOpen();
        assertThrows(CircuitOpenException.class, cb::acquire);
        cb.disable();
        record(true);
        assertEquals(0, cb.snapshot().calls());
        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.snapshot().state());
    }
}
