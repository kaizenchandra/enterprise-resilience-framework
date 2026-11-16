package dev.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

class BulkheadTest {
    Deadline deadline() {
        return new Deadline(Duration.ofSeconds(2), Ticker.SYSTEM);
    }

    @Test
    void exactlyOnceAndException() {
        var b = new Bulkhead(1, Duration.ZERO, true);
        var p = assertDoesNotThrow(() -> b.acquire(deadline()));
        assertThrows(RejectedExecutionException.class, () -> b.acquire(deadline()));
        p.close();
        p.close();
        assertEquals(1, b.available());
        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (var permit = b.acquire(deadline())) {
                        throw new IllegalStateException();
                    }
                });
        assertEquals(1, b.available());
    }

    @Test
    void interruptedWait() throws Exception {
        var b = new Bulkhead(1, Duration.ofSeconds(2), true);
        try (var p = b.acquire(deadline())) {
            var entered = new CountDownLatch(1);
            var interrupted = new AtomicBoolean();
            Thread t =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        entered.countDown();
                                        try (var ignored = b.acquire(deadline())) {
                                        } catch (InterruptedException e) {
                                            interrupted.set(true);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
            entered.await();
            t.interrupt();
            t.join(1000);
            assertTrue(interrupted.get());
            assertEquals(0, b.available());
        }
        assertEquals(1, b.available());
    }
}
