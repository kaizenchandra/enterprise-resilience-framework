# Mandatory architecture review

Answers combine architecture, concurrency, failure behavior, performance and production trade-offs. Supporting
implementation details are in the linked topic guides.

## 1. What problem does a circuit breaker solve?

Stops repeatedly calling an unhealthy dependency; local admission state is atomic. OPEN rejects immediately, reducing
load and latency at the cost of temporary unavailability.

## 2. What are CLOSED, OPEN and HALF_OPEN?

CLOSED records eligible outcomes; OPEN rejects; HALF_OPEN allocates bounded probes. State transitions reset the ring and
generation, avoiding stale recovery evidence. Conservative probes sacrifice availability for safety.

## 3. How are transitions atomic?

One monitor protects all fields and admission; generation invalidates old tickets. No user I/O inside the lock. This
linearizable approach costs contention, which needs measured optimization.

## 4. How does a count window work?

A fixed byte ring evicts failure/slow bits, subtracts them, and adds one eligible completed outcome. Constant-time
locked update gives exact bounded completion-order accounting; traffic age is not represented.

## 5. How does a time window work?

Rotate bounded time buckets and subtract expired totals under coordinated synchronization. Idle periods expire old
evidence; coarse buckets approximate boundaries. Not implemented in this release.

## 6. Why minimum calls?

Percentages on one request are unstable. Evaluate only after enough eligible samples under the same lock. Warmup costs
detection delay and permits some failures before opening.

## 7. How should slow calls affect decisions?

Successful but slow calls consume capacity. Record a separate slow bit and open on slow percentage after minimum calls.
Duration thresholds must reflect SLOs; aggressive limits reject a functioning dependency.

## 8. How do you limit half-open probes?

Allocate under the circuit monitor, reject when probe budget is full. Count successful completions; ignored results
return slots. This caps probes per local instance, not across the fleet.

## 9. How do you handle late probes?

Ticket captures generation; record compares it under the monitor and discards stale outcomes. An atomic completion flag
prevents duplicate accounting. Old physical work still consumes its permit until exit.

## 10. How do you prevent state races?

Keep state, counters, probe allocation and generation within one monitor; do not assemble rates from independent adders.
Coherent snapshots trade maximum throughput for simple correctness.

## 11. What is exponential backoff?

Delay grows base times powers of two, capped before arithmetic overflow. Bounded attempts/time stop amplification.
Delays add latency, so they must share the caller budget.

## 12. Why jitter?

Random full jitter spreads retries from synchronized clients. Per-thread RNG avoids a central lock. It reduces
correlated bursts but cannot replace a global downstream quota.

## 13. What is retry amplification?

Attempts exceed logical calls. Even bounded local retries can exhaust global capacity; observe retry/operation ratios
and downstream counts. Availability gains have a load cost.

## 14. How do nested retries multiply?

Three attempts at each of three hops yield up to 27 leaf attempts. Local thread safety does not prevent this; designate
one retry owner. This release documents ownership rather than enforcing it.

## 15. When not to retry?

UNSAFE operations, business failures, local admission rejection, cancellation, expired budget, and permanent errors.
Propagate the original exception where possible; fewer retries preserve downstream capacity.

## 16. Why are payments sensitive?

A timed-out capture may already be committed. Client retries cannot ensure exactly-once behavior. Require atomic
idempotency and reconciliation or make one attempt; latency recovery must not create duplicate financial effects.

## 17. How implement retry budgets?

Bound attempts, elapsed time, backoff and shared overall deadline. Redis-backed atomic token buckets additionally bound
fleet amplification across replicas; lookup failure suppresses additional attempts. Per-call bounds alone cannot
establish global load safety.

## 18. How handle Retry-After?

Validated server hint is a lower bound on delay; if it cannot fit, do not retry. HTTP adapters must parse seconds/date
using wall time while deadlines remain monotonic. Demo supports fixed seconds only.

## 19. What is a semaphore bulkhead?

Finite permits limit active dependency work. Interruptible timed acquisition rejects on saturation; idempotent close
releases on physical exit. It is cheap but does not isolate CPU or carrier threads.

## 20. What is a thread-pool bulkhead?

Dedicated bounded workers and queue isolate execution resources. Rejection sheds excess work and cancellation is
cooperative. This implementation uses shared fixed virtual workers with no queue; endpoint-specific pools are deferred.

## 21. When use each bulkhead?

Use semaphores for downstream concurrency regardless of threading model; dedicated pools for legacy blocking isolation
when justified. More pools improve isolation but consume memory and operational capacity.

## 22. How prevent permit leaks?

Acquire inside the actual worker and close in try-with-resources; AtomicBoolean prevents double close. Caller timeout
never releases physical capacity early. Hung operations intentionally retain permits until termination.

## 23. How do virtual threads change bulkheads?

They lower blocking thread cost, so explicit semaphore limits become essential. Monitor sections must remain short to
avoid pinning. CPU and downstream quotas still define capacity.

## 24. Why do virtual threads not remove capacity limits?

DB connections, CPU, memory and remote quotas are independent of thread implementation. Excess cheap threads can
increase backlog; use no-queue bounded workers and connection-pool limits.

## 25. Timeout versus cancellation?

Timeout ends a caller wait; cancellation requests the task stop. Neither proves downstream work stopped. Retained
physical permits bound late work, but availability can remain low until the operation exits.

## 26. Why can timed-out work continue?

Java interruption is cooperative; native calls or application loops may ignore it and remote work may already commit. Do
not kill threads. Use transport deadlines and server reconciliation; monitor retained capacity.

## 27. How propagate deadlines?

Transmit remaining bounded duration, clamp untrusted values, subtract transport reserve; never share nanoTime values
across hosts. The starter filter and HTTP adapter propagate clamped remaining durations and W3C trace context. Unknown
transit delay and unsupported clients remain adoption concerns.

## 28. What is correct composition order?

No universal order. Default deadline surrounds fallback and retry; each attempt checks circuit then semaphore and
timeout. It counts each eligible attempt, prioritizing dependency protection over hiding transient failures.

## 29. Count attempts or logical calls?

This implementation counts attempts; logical accounting can mask failing retries behind eventual success. Both require
coherent denominator rules and separate logical latency metrics. Choose semantics explicitly before tuning thresholds.

## 30. When is fallback safe?

Only with explicit exception eligibility, remaining budget, and business permission for degraded data. Fallback runs
outside circuit accounting on bounded workers. Cached data must still satisfy authorization and freshness rules.

## 31. Why dangerous for finance?

A fabricated success or balance misrepresents committed truth. Concurrency-safe fallback code can still be semantically
wrong. Return explicit unavailable/ambiguous status and reconcile rather than masking failure.

## 32. How scope breakers?

Use bounded configured dependency-operation names matching failure domains. Shared state across unrelated operations
causes collateral shedding; arbitrary request keys cause memory/cardinality growth. Registry capacity bounds identity
growth.

## 33. Should state be distributed?

Default local state avoids network coordination and coordinator outages. Fleet health signals can guide routing, but
global state can overreact to partial failure and becomes another dependency. No distributed breaker is implemented.

## 34. How does Spring AOP affect resilience?

Only calls crossing a supported proxy are intercepted; self/final/nonpublic invocation cannot be assumed protected.
Advice offloads work, so the starter propagates deadline/MDC/OpenTelemetry state, while other thread locals need
explicit integration. Async annotations are rejected; use programmatic stage adapter.

## 35. How test transitions deterministically?

Inject monotonic Ticker and advance it without long sleeps; coordinate contenders with latches and retain probe tickets
to force stale completion order. Real timed waits still require bounded integration tests and stress runs.

## 36. How benchmark correctly?

JMH warmup/measurement/forks, returned results, shared-state contention and GC profiling; pin JVM/hardware/dependencies.
Short smoke results cannot predict tail latency or scale; use offered-load tests plus host telemetry.

## 37. What are Resilience4j advantages?

Broader state/window, reactive, Spring and observability integration with operational history. Library maturity reduces
custom race/maintenance risk; still configure safe retry, deadlines and downstream quotas yourself.

## 38. What is custom maintenance cost?

Team owns concurrency proofs, API compatibility, cancellation/client behavior, patches and operational documentation.
Lightweight dependencies do not imply low lifecycle cost. Budget sustained reviews and incident ownership before
adoption.

## 39. What guarantees are realistic?

Local linearizable circuit admission, bounded window/workers/attempts, idempotent permit release and cooperative
cancellation. No arbitrary-code termination, exactly-once effects, fleet-wide limits or guaranteed latency improvement.

## 40. What evidence before production?

Reproducible full build, concurrency stress, cancellation/transport tests, soak/load results, security review,
dashboards/alerts, rollout recovery and rollback drills. A passing unit suite or local demo is insufficient to establish
production readiness.

