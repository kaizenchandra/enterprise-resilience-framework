package dev.resilience;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * Fixed-size virtual-thread workers, no queue. Cancellation does not free a worker until user code
 * exits.
 */
public final class TimeLimiter implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final TaskContext context;

    public TimeLimiter(int capacity) {
        this(capacity, TaskContext.FRAMEWORK);
    }

    public TimeLimiter(int capacity, TaskContext context) {
        this.context = context;
        Checks.range(capacity, 10000);
        workers =
                new ThreadPoolExecutor(
                        capacity,
                        capacity,
                        30,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofVirtual()
                                .inheritInheritableThreadLocals(false)
                                .name("resilience-attempt-", 0)
                                .factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    public <T> T call(Callable<T> action, Deadline deadline, Duration timeout) throws Exception {
        Checks.duration(timeout, false);
        deadline.check();
        var callDeadline = deadline.capped(timeout);
        Future<T> task =
                workers.submit(
                        context
                                .capture()
                                .wrap(
                                        () -> {
                                            try (var scope = ResilienceContext.withDeadline(callDeadline)) {
                                                return action.call();
                                            }
                                        }));
        try {
            return task.get(callDeadline.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception x) throw x;
            if (cause instanceof Error x) throw x;
            throw new IllegalStateException(cause);
        } catch (TimeoutException | InterruptedException e) {
            task.cancel(true);
            throw e;
        }
    }

    public int active() {
        return workers.getActiveCount();
    }

    public void close() {
        for (Runnable r : workers.shutdownNow()) if (r instanceof Future<?> f) f.cancel(true);
    }
}
