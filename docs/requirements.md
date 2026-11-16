# Contracts and acceptance evidence

Functional: named local circuits, bounded retries, semaphore isolation, attempt/overall timeout, explicit fallback,
blocking and CompletableFuture APIs, Spring binding/AOP, metrics and authenticated fault controls.

Nonfunctional: no Spring dependency below starter/observability; Java 21; count window capped at 100,000 entries;
registry capped at 1,000; worker/async capacities capped at 10,000; attempts capped at ten; duration capped at one day.
These validation ceilings are not recommended capacity settings.

Invariants:

1. Circuit mutations and admission are linearizable under one monitor.
2. A ticket records at most once and only in its own generation.
3. HALF_OPEN issues at most configured outstanding/accepted probes; ignored probes release their slot. All required
   successful probes must complete before closing.
4. OPEN rejection and local admission rejection never enter the failure denominator.
5. Each acquired semaphore permit releases at most once, after actual action exit.
6. Every retry consumes the same overall deadline and retry elapsed budget.
7. UNSAFE operations make one attempt regardless of exception.
8. Fallback is explicitly eligible, consumes the remaining deadline, and never records dependency success.
9. No executor queue or asynchronous admission backlog exists.
10. Registry update publishes a fully initialized snapshot atomically; calls retain the old snapshot.

Acceptance maps to CircuitBreakerTest, RetryPolicyTest, BulkheadTest, ExecutorTest, StarterTest, HttpIntegrationTest.
Deployment and scale evidence are separate release gates; see verification.md. Business idempotency cannot be
established by a library enum alone.
