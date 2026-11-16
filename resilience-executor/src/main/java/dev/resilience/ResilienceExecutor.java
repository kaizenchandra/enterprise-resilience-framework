package dev.resilience;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking API: do not invoke on event loops. Async adapter is bounded and cancellation interrupts
 * its coordinator.
 */
public final class ResilienceExecutor implements AutoCloseable {
    private final PolicyRegistry registry;
    private final RetryBudget budget;
    private final TimeLimiter budgetLimiter;
    private final TaskContext context;
    private final TimeLimiter limiter;
    private final Ticker ticker;
    private final Sleeper sleeper;
    private final EventSink events;
    private final FailureClassifier classifier;
    private final ThreadPoolExecutor coordinators;

    public ResilienceExecutor(
            PolicyRegistry registry, int workers, int asyncCalls, EventSink events) {
        this(
                registry,
                workers,
                asyncCalls,
                events,
                Ticker.SYSTEM,
                Sleeper.SYSTEM,
                FailureClassifier.DEFAULT);
    }

    public ResilienceExecutor(
            PolicyRegistry registry,
            int workers,
            int asyncCalls,
            EventSink events,
            Ticker ticker,
            Sleeper sleeper,
            FailureClassifier classifier) {
        this(
                registry,
                workers,
                asyncCalls,
                events,
                ticker,
                sleeper,
                classifier,
                RetryBudget.UNLIMITED,
                TaskContext.FRAMEWORK);
    }

    public ResilienceExecutor(
            PolicyRegistry registry,
            int workers,
            int asyncCalls,
            EventSink events,
            Ticker ticker,
            Sleeper sleeper,
            FailureClassifier classifier,
            RetryBudget budget,
            TaskContext context) {
        this.budget = java.util.Objects.requireNonNull(budget);
        this.context = java.util.Objects.requireNonNull(context);
        this.budgetLimiter = new TimeLimiter(8, context);
        this.registry = registry;
        limiter = new TimeLimiter(workers, context);
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.events = EventSink.safe(events);
        this.classifier = classifier;
        Checks.range(asyncCalls, 10000);
        coordinators =
                new ThreadPoolExecutor(
                        asyncCalls,
                        asyncCalls,
                        30,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofVirtual()
                                .inheritInheritableThreadLocals(false)
                                .name("resilience-coordinator-", 0)
                                .factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        coordinators.allowCoreThreadTimeOut(true);
    }

    public <T> T execute(String name, RetrySafety safety, Callable<T> action) throws Exception {
        return execute(name, safety, action, null);
    }

    public <T> T execute(String name, RetrySafety safety, Callable<T> action, Fallback<T> fallback)
            throws Exception {
        return execute(name, safety, action, fallback, null);
    }

    /**
     * A supplied monotonic deadline can only shorten the configured overall budget.
     */
    public <T> T execute(
            String name,
            RetrySafety safety,
            Callable<T> action,
            Fallback<T> fallback,
            Deadline parentDeadline)
            throws Exception {
        java.util.Objects.requireNonNull(safety, "retry safety");
        java.util.Objects.requireNonNull(action, "action");
        var p = registry.get(name);
        var c = p.config();
        if (parentDeadline == null) parentDeadline = ResilienceContext.deadline();
        if (parentDeadline != null) parentDeadline.check();
        var deadline =
                parentDeadline == null
                        ? new Deadline(c.overall(), ticker)
                        : parentDeadline.capped(c.overall());
        long start = ticker.read();
        try (var callScope = ResilienceContext.withDeadline(deadline)) {
            return run(name, p, safety, action, deadline, start);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            if (failure instanceof CancellationException) throw failure;
            if (fallback != null && fallback.eligible(failure)) {
                deadline.check();
                events.accept(name, "fallback", 1);
                try (var fallbackScope = ResilienceContext.withDeadline(deadline)) {
                    return limiter.call(() -> fallback.apply(failure), deadline, c.perAttempt());
                }
            }
            throw failure;
        } finally {
            events.accept(name, "latency", ticker.read() - start);
        }
    }

    private <T> T run(
            String name,
            PolicyRegistry.Policy p,
            RetrySafety safety,
            Callable<T> action,
            Deadline deadline,
            long start)
            throws Exception {
        var c = p.config();
        Deadline retryDeadline = deadline.capped(c.retry().maxElapsed());
        Exception lastFailure = null;
        for (int attempt = 1; ; attempt++) {
            retryDeadline.check();
            deadline.check();
            if (attempt > 1 && ticker.read() - start >= c.retry().maxElapsed().toNanos())
                throw new TimeoutException("Retry elapsed budget exhausted");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (attempt > 1) {
                RetryBudget.Decision decision;
                try {
                    decision =
                            budgetLimiter.call(
                                    () -> budget.acquire(name, retryDeadline),
                                    retryDeadline,
                                    java.time.Duration.ofMillis(150));
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception unavailable) {
                    decision = RetryBudget.Decision.UNAVAILABLE;
                }
                if (decision != RetryBudget.Decision.ALLOWED) {
                    events.accept(name, "budget_" + decision.name().toLowerCase(java.util.Locale.ROOT), 1);
                    throw lastFailure;
                }
                events.accept(name, "retry", 1);
            }
            CircuitBreaker.Ticket ticket;
            try {
                ticket = p.circuit().acquire();
            } catch (CircuitOpenException e) {
                events.accept(name, "circuit_rejected", 1);
                throw e;
            }
            long began = ticker.read();
            var attemptDeadline = retryDeadline.capped(c.perAttempt());
            try {
                T result =
                        limiter.call(
                                () -> {
                                    try (var attemptScope = ResilienceContext.withDeadline(attemptDeadline);
                                         var permit = p.bulkhead().acquire(attemptDeadline)) {
                                        attemptDeadline.check();
                                        return action.call();
                                    }
                                },
                                attemptDeadline,
                                c.perAttempt());
                retryDeadline.check();
                ticket.complete(FailureClassifier.Outcome.SUCCESS);
                events.accept(name, "success", 1);
                return result;
            } catch (Exception e) {
                boolean local =
                        e instanceof RejectedExecutionException
                                || e instanceof InterruptedException
                                || e instanceof CancellationException;
                var outcome = local ? FailureClassifier.Outcome.IGNORE : classifier.classify(e);
                ticket.complete(outcome);
                events.accept(
                        name,
                        local
                                ? "rejected"
                                : e instanceof TimeoutException
                                ? "timeout"
                                : outcome == FailureClassifier.Outcome.FAILURE ? "failure" : "ignored",
                        1);
                if (e instanceof InterruptedException) throw e;
                if (safety == RetrySafety.UNSAFE
                        || local
                        || outcome != FailureClassifier.Outcome.FAILURE
                        || attempt >= c.retry().maxAttempts()) {
                    events.accept(name, "exhausted", 1);
                    throw e;
                }
                long delay = c.retry().delayNanos(attempt);
                if (e instanceof RetryAfterException r) delay = Math.max(delay, r.delay().toNanos());
                long retryRemaining = c.retry().maxElapsed().toNanos() - (ticker.read() - start);
                if (delay >= deadline.remainingNanos() || delay >= retryRemaining) throw e;
                lastFailure = e;
                events.accept(name, "retry_delay", delay);
                sleeper.sleep(delay);
                deadline.check();
            } catch (Error fatal) {
                ticket.complete(FailureClassifier.Outcome.IGNORE);
                throw fatal;
            } finally {
                if (ticker.read() - began >= c.circuit().slowDuration().toNanos())
                    events.accept(name, "slow", 1);
            }
        }
    }

    public <T> CompletableFuture<T> executeAsync(
            String name, RetrySafety safety, Callable<T> action) {
        var handle = new AtomicReference<Future<?>>();
        CompletableFuture<T> result =
                new CompletableFuture<>() {
                    public boolean cancel(boolean interrupt) {
                        boolean changed = super.cancel(interrupt);
                        Future<?> f = handle.get();
                        if (changed && f != null) f.cancel(interrupt);
                        return changed;
                    }
                };
        TaskContext.Snapshot snapshot;
        try {
            var inherited = ResilienceContext.deadline();
            var duration = registry.get(name).config().overall();
            var submittedDeadline =
                    inherited == null ? new Deadline(duration, ticker) : inherited.capped(duration);
            try (var scope = ResilienceContext.withDeadline(submittedDeadline)) {
                snapshot = context.capture();
            }
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            return result;
        }
        try {
            Future<?> task =
                    coordinators.submit(
                            () -> {
                                try (var scope = snapshot.open()) {
                                    result.complete(execute(name, safety, action));
                                } catch (Throwable e) {
                                    result.completeExceptionally(e);
                                }
                            });
            handle.set(task);
            if (result.isCancelled()) task.cancel(true);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Adapts a stage-producing client. Client cancellation remains cooperative.
     */
    public <T> CompletableFuture<T> executeStage(
            String name,
            RetrySafety safety,
            java.util.function.Supplier<? extends CompletionStage<T>> action) {
        return executeAsync(
                name,
                safety,
                () -> {
                    var stage = action.get().toCompletableFuture();
                    try {
                        return stage.get();
                    } catch (InterruptedException e) {
                        stage.cancel(true);
                        throw e;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof Exception x) throw x;
                        if (e.getCause() instanceof Error x) throw x;
                        throw new IllegalStateException(e.getCause());
                    }
                });
    }

    public void close() {
        coordinators.shutdownNow();
        limiter.close();
        budgetLimiter.close();
    }
}
