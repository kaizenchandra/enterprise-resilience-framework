package dev.resilience;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Bulkhead {
    private final Semaphore semaphore;
    private final int capacity;
    private final Duration wait;

    public Bulkhead(int capacity, Duration wait, boolean fair) {
        this.capacity = Checks.range(capacity, 100000);
        this.wait = Checks.duration(wait, true);
        semaphore = new Semaphore(capacity, fair);
    }

    public Permit acquire(Deadline deadline) throws InterruptedException, TimeoutException {
        deadline.check();
        boolean acquired =
                semaphore.tryAcquire(
                        Math.min(wait.toNanos(), deadline.remainingNanos()), TimeUnit.NANOSECONDS);
        if (!acquired) throw new RejectedExecutionException("Bulkhead saturated");
        return new Permit();
    }

    public int active() {
        return capacity - semaphore.availablePermits();
    }

    public int available() {
        return semaphore.availablePermits();
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        public void close() {
            if (closed.compareAndSet(false, true)) semaphore.release();
        }
    }
}
