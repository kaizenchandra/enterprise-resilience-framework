# Failure scenario review

Automated evidence is named precisely. A pending test is a release gap, not a claimed pass. Prevention/recovery includes
operational responsibilities.

## 1. 50% failures

- Mechanism: Eligible failures fill ring.
- Correctness: Minimum denominator and failure bits must agree.
- Performance and user behavior: Retries amplify; then fail fast.
- Detection: failure rate, circuit state.
- Prevention and recovery: Open at threshold; bounded recovery probes.
- Automated evidence: CircuitBreakerTest.minimumAndWindow.
- Residual risk: Threshold/window tuning.

## 2. Very slow dependency

- Mechanism: Latency consumes workers.
- Correctness: Slow success can open circuit.
- Performance and user behavior: Tail latency; timed-out callers.
- Detection: slow rate, timeout, active work.
- Prevention and recovery: Transport deadlines; slow threshold; recover with probes.
- Automated evidence: CircuitBreakerTest.slowCalls; HttpIntegrationTest.socketTimeout.
- Residual risk: Server may continue.

## 3. Circuit opens with in-flight calls

- Mechanism: Concurrent old admissions finish.
- Correctness: Generation rejects late outcomes.
- Performance and user behavior: No forced termination; new calls rejected.
- Detection: state and active permits.
- Prevention and recovery: Retain physical permits; drain existing work.
- Automated evidence: CircuitBreakerTest.staleResultsAndRecovery.
- Residual risk: Work admitted before open continues.

## 4. Simultaneous half-open admission

- Mechanism: 100 contenders after open duration.
- Correctness: Atomic bounded allocation.
- Performance and user behavior: Most callers rejected quickly.
- Detection: rejected/state.
- Prevention and recovery: Monitor serializes; allow limited probes.
- Automated evidence: CircuitBreakerTest.halfOpenConcurrentAdmission.
- Residual risk: Lock contention.

## 5. Out-of-order probes

- Mechanism: Probe completion order differs.
- Correctness: Close only on required successes.
- Performance and user behavior: Recovery waits for slowest admitted probe.
- Detection: HALF_OPEN duration.
- Prevention and recovery: Generation tickets; failed probe reopens.
- Automated evidence: CircuitBreakerTest.staleResultsAndRecovery.
- Residual risk: Ignored probes can extend recovery.

## 6. Cancelled probe

- Mechanism: Caller interruption.
- Correctness: Ignore and return probe slot.
- Performance and user behavior: No fake recovery evidence.
- Detection: ignored/cancellation.
- Prevention and recovery: Idempotent ticket complete; next admission replaces slot.
- Automated evidence: ExecutorTest.asyncCancellation; CircuitBreakerTest.ignoredProbeReturnsSlot (ignored cancellation
  classification).
- Residual risk: Transport may ignore cancellation.

## 7. Old failure after close

- Mechanism: Previous-generation completion.
- Correctness: Cannot corrupt new CLOSED ring.
- Performance and user behavior: No new open from stale result.
- Detection: transition count.
- Prevention and recovery: Version ticket invalidation.
- Automated evidence: CircuitBreakerTest.staleResultsAndRecovery.
- Residual risk: Explicit reset clears history.

## 8. Window counters race

- Mechanism: Concurrent completions.
- Correctness: Numerator and denominator must be atomic.
- Performance and user behavior: Monitor contention.
- Detection: consistent snapshots.
- Prevention and recovery: One monitor; O(1) ring update.
- Automated evidence: CircuitBreakerTest.ringUnderPlatformAndVirtualConcurrency.
- Residual risk: More stress architectures needed.

## 9. Ignored exceptions

- Mechanism: Business failure not dependency failure.
- Correctness: Exclude numerator and denominator.
- Performance and user behavior: Business error propagated, no retry.
- Detection: ignored count.
- Prevention and recovery: Explicit classifier; audit exception taxonomy.
- Automated evidence: CircuitBreakerTest.ignoreAndExactlyOnce.
- Residual risk: Misclassification masks outage.

## 10. HTTP 429

- Mechanism: Rate-limited downstream.
- Correctness: Only safe calls retry.
- Performance and user behavior: More delay, bounded requests.
- Detection: status, retry count.
- Prevention and recovery: Retry-After lower bound and budget.
- Automated evidence: HttpIntegrationTest.retryHttp429.
- Residual risk: Per-call budgets not global quotas.

## 11. 503 Retry-After

- Mechanism: Temporary unavailable response.
- Correctness: Respect server hint.
- Performance and user behavior: Can exhaust budget before retry.
- Detection: retry_delay.
- Prevention and recovery: Wait max(local,hint), else propagate.
- Automated evidence: HttpIntegrationTest.retryHttp503; RetryPolicyTest.
- Residual risk: HTTP-date parser not included.

## 12. Timeout after downstream commit

- Mechanism: Response lost after side effect.
- Correctness: Outcome ambiguous.
- Performance and user behavior: Caller sees timeout.
- Detection: trace and server reconciliation.
- Prevention and recovery: UNSAFE one attempt; reconcile operation identity.
- Automated evidence: ExecutorTest.retrySafety.
- Residual risk: No exactly-once guarantee.

## 13. Duplicate payments

- Mechanism: Unsafe retries repeat capture.
- Correctness: Do not claim payment succeeded.
- Performance and user behavior: Duplicate financial harm.
- Detection: Idempotency/reconciliation records.
- Prevention and recovery: Verified atomic deduplication; disable retries.
- Automated evidence: ExecutorTest.retrySafety.
- Residual risk: Application contract outside library.

## 14. Retries at three layers

- Mechanism: 3 cubed attempts.
- Correctness: Bound each layer but total can be 27.
- Performance and user behavior: Amplification storm.
- Detection: per-hop retry ratio.
- Prevention and recovery: Single retry owner; gateway one attempt.
- Automated evidence: Demo configuration; nested load test pending.
- Residual risk: Ownership not automatically enforced.

## 15. Backoff exceeds deadline

- Mechanism: Delay consumes remaining budget.
- Correctness: No additional attempt admitted.
- Performance and user behavior: Original error or timeout.
- Detection: deadline/exhausted metrics.
- Prevention and recovery: Reject delay if it cannot fit.
- Automated evidence: ExecutorTest.deterministicBudget.
- Residual risk: Scheduler latency can exceed caller budget.

## 16. Retry storm on recovery

- Mechanism: Synchronized retries/probes.
- Correctness: Per-instance probe bounds hold.
- Performance and user behavior: Many pods can still burst.
- Detection: aggregate downstream QPS.
- Prevention and recovery: Full jitter; downstream quotas; gradual rollout.
- Automated evidence: RetryPolicyTest.jitterBounds.
- Residual risk: RedisRetryBudget caps shared retries; Redis state-loss remains a risk.

## 17. Bulkhead saturated

- Mechanism: All physical permits occupied.
- Correctness: Reject without dependency failure.
- Performance and user behavior: Fast 503; reduced throughput.
- Detection: available=0, rejections.
- Prevention and recovery: Bounded wait; capacity plan; drain.
- Automated evidence: BulkheadTest.exactlyOnceAndException.
- Residual risk: Shared worker starvation possible.

## 18. Permit leak after exception

- Mechanism: Exceptional action exit.
- Correctness: Exactly one close per acquisition.
- Performance and user behavior: Capacity must recover.
- Detection: active gauge stays high.
- Prevention and recovery: try-with-resources in worker.
- Automated evidence: BulkheadTest.exactlyOnceAndException.
- Residual risk: Arbitrary stuck code retains permit intentionally.

## 19. Cancelled bulkhead acquisition

- Mechanism: Interrupt during timed wait.
- Correctness: No permit assigned or leaked.
- Performance and user behavior: Prompt cancellation.
- Detection: cancel and available count.
- Prevention and recovery: Semaphore interruptible timed tryAcquire.
- Automated evidence: BulkheadTest.interruptedWait.
- Residual risk: Interrupt race requires stress.

## 20. Executor queue full

- Mechanism: No-queue workers all active.
- Correctness: Reject locally, ignore for circuit.
- Performance and user behavior: Fast shedding.
- Detection: rejected events.
- Prevention and recovery: SynchronousQueue AbortPolicy; size capacity.
- Automated evidence: TransportChaosTest.boundedWorkersRejectAndShutdownInterruptsActiveWork.
- Residual risk: Global capacity affects unrelated policies.

## 21. Virtual threads overwhelm DB

- Mechanism: Cheap threads exceed connections.
- Correctness: Still require semaphore.
- Performance and user behavior: Connection wait and high latency.
- Detection: pool occupancy and bulkhead active.
- Prevention and recovery: Limit concurrency to DB budget.
- Automated evidence: ConnectionPoolChaosTest uses Hikari/H2 saturation on a virtual worker.
- Residual risk: External pool configuration.

## 22. Timeout but work continues

- Mechanism: Code ignores interrupt.
- Correctness: Permit held until real exit.
- Performance and user behavior: Caller fails; capacity stays used.
- Detection: timeouts plus active work.
- Prevention and recovery: Client-level timeout; repair hung dependency.
- Automated evidence: ExecutorTest.timeoutRetainsPermitUntilActualExit.
- Residual risk: Cannot kill arbitrary Java code.

## 23. Unauthorized stale fallback

- Mechanism: Cache bypasses current authorization.
- Correctness: Security breach despite availability.
- Performance and user behavior: Fast but invalid response.
- Detection: access audit.
- Prevention and recovery: Reauthorize cached data; disallow sensitive fallback.
- Automated evidence: Fallback test covers eligibility; auth-cache test pending.
- Residual risk: Domain validation required.

## 24. Config update in flight

- Mechanism: Snapshot replaced.
- Correctness: Existing calls retain old resources.
- Performance and user behavior: Temporary overlapping capacities.
- Detection: version and active work.
- Prevention and recovery: Validate; drain; publish once; rollback snapshot.
- Automated evidence: ExecutorTest.immutableUpdateAndBounds.
- Residual risk: Old work absent from new gauges.

## 25. Refresh resets circuit

- Mechanism: New runtime starts CLOSED.
- Correctness: Document reset semantics.
- Performance and user behavior: Cold traffic burst.
- Detection: version/state reset.
- Prevention and recovery: Drain and audit; avoid frequent refresh.
- Automated evidence: ExecutorTest.immutableUpdateAndBounds.
- Residual risk: State continuity not implemented.

## 26. AOP self invocation

- Mechanism: Proxy bypass.
- Correctness: Internal call unprotected.
- Performance and user behavior: No resilience overhead or guard.
- Detection: Circuit call count.
- Prevention and recovery: Use injected external collaborator or programmatic API.
- Automated evidence: StarterTest.proxyAndSelfInvocation.
- Residual risk: Final/nonpublic methods also limited.

## 27. Late CompletableFuture

- Mechanism: Async client completes after timeout.
- Correctness: No second circuit record.
- Performance and user behavior: Late result discarded.
- Detection: timeout vs client activity.
- Prevention and recovery: Cancel stage; ticket exactly once.
- Automated evidence: ExecutorTest.asyncCancellation; stageAdapter.
- Residual risk: TransportChaosTest.cancelledStageReceivesCancellation; transport abort remains client-dependent.

## 28. Shutdown during operation

- Mechanism: Workers interrupted.
- Correctness: Bounded tasks may outlive shutdown.
- Performance and user behavior: Caller cancellation/timeout.
- Detection: shutdown logs and active work.
- Prevention and recovery: Stop admission; grace period; transport timeouts.
- Automated evidence: TransportChaosTest.boundedWorkersRejectAndShutdownInterruptsActiveWork.
- Residual risk: Noncooperative code can remain.

## 29. Partial recovery

- Mechanism: Some probes still fail.
- Correctness: Any failed/slow probe reopens.
- Performance and user behavior: Continued fail-fast availability loss.
- Detection: HALF_OPEN/OPEN transitions.
- Prevention and recovery: Conservative probes; tune slow threshold.
- Automated evidence: CircuitBreakerTest.staleResultsAndRecovery.
- Residual risk: Probe sample may be biased.

## 30. Circuit oscillates

- Mechanism: Aggressive thresholds and short waits.
- Correctness: State stays valid but unstable.
- Performance and user behavior: Bursty traffic, intermittent errors.
- Detection: transition rate.
- Prevention and recovery: Larger window/minimum/open wait; investigate.
- Automated evidence: State tests; oscillation soak pending.
- Residual risk: No adaptive hysteresis controller.

## 31. Monitoring unavailable

- Mechanism: Sink/export fails.
- Correctness: Business work must continue.
- Performance and user behavior: Lose visibility; blocking sink can add latency.
- Detection: Exporter health.
- Prevention and recovery: Safe sink wrapper; nonblocking exporter.
- Automated evidence: ExecutorTest.telemetryFailureIsIsolated.
- Residual risk: Errors/blocking in sink still harmful.

## 32. Pod restarts cold

- Mechanism: Local circuit state lost.
- Correctness: New pod starts CLOSED.
- Performance and user behavior: Surge before threshold.
- Detection: rollout and downstream QPS.
- Prevention and recovery: Gradual rollout; semaphore and external quotas.
- Automated evidence: Live Kubernetes payment removal/restoration and order rolling restart.
- Residual risk: No persisted state.

## 33. Network partition

- Mechanism: IO failures or long connect waits.
- Correctness: Classify and open locally.
- Performance and user behavior: Timeout/rejection until healing.
- Detection: IO/timeouts state.
- Prevention and recovery: Bound transport waits; probe after duration.
- Automated evidence: HttpIntegrationTest.connectionRefused/socketTimeout.
- Residual risk: Live Kubernetes removes payment endpoints; multi-node partition remains pending.

## 34. Mixed deployment configs

- Mechanism: Pods use different snapshots.
- Correctness: Each local invariant holds.
- Performance and user behavior: Uneven shedding and retries.
- Detection: Config version per pod.
- Prevention and recovery: Audit rollout, canary, rollback.
- Automated evidence: Version test; mixed rollout test pending.
- Residual risk: No global version coordinator.

## 35. High volume monopolizes capacity

- Mechanism: Shared workers or shared policy.
- Correctness: No per-endpoint fairness guarantee.
- Performance and user behavior: Other endpoints starve.
- Detection: policy saturation and traffic split.
- Prevention and recovery: Scope by operation; partition pools externally.
- Automated evidence: Bulkhead tests; fairness load pending.
- Residual risk: Dedicated pool extension may be needed.

