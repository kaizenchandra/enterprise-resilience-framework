# MASTER PROMPT: Design and Develop a Production-Grade Circuit Breaker, Retry, and Bulkhead Framework for Microservices Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Enterprise Solution Architect, Java
21 Concurrency Expert, Spring Boot Architect, Reliability Engineer, Framework Designer, and Site Reliability Engineer**
with 15+ years of experience designing fault-tolerant, high-throughput enterprise microservices and reusable
infrastructure libraries.

Your task is to **design, implement, test, benchmark, document, and deploy an enterprise-grade Resilience Framework for
Java 21 and Spring Boot microservices**.

The framework must provide:

1. Circuit Breaker
2. Retry
3. Bulkhead
4. Timeout / Time Limiter
5. Fallback
6. Rate Limiting as an optional extension
7. Composable resilience policies
8. Thread-safe execution
9. Synchronous and asynchronous support
10. Java 21 virtual-thread compatibility
11. Spring Boot auto-configuration
12. Programmatic APIs
13. Annotation-based APIs where justified
14. Dynamic configuration
15. Metrics and tracing
16. Fault injection and chaos testing
17. Performance benchmarking
18. Docker and Kubernetes demonstration
19. Complete source code and documentation

Treat this as a production-oriented framework development project, not merely a system design interview exercise.

**Do not stop at HLD, LLD, pseudocode, or illustrative examples. Deliver a reusable, independently testable Java
library, Spring Boot integration, example microservices, automated tests, benchmark scripts, and deployment
infrastructure.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow existing repository conventions, dependency versions, architecture, and coding standards.
- Apply SOLID, Clean Architecture, Hexagonal Architecture, and separation of concerns pragmatically.
- Keep the core framework independent of Spring Boot.
- Prefer composition over inheritance.
- Do not invent unnecessary abstractions or generic extension mechanisms.
- Use immutable configuration objects.
- Provide clear public API contracts.
- Use bounded concurrency, queues, retries, waits, and memory.
- Handle cancellation, interruption, deadlines, timeouts, and race conditions explicitly.
- Avoid blocking reactive/event-loop threads.
- Never retry unsafe non-idempotent operations automatically.
- Never treat fallback responses as successful dependency calls unless explicitly configured and semantically justified.
- Do not assume virtual threads remove CPU, memory, connection-pool, or downstream capacity limits.
- Never fabricate executed tests, performance measurements, or production readiness.
- Explain important implementation trade-offs and residual risks.

---

# PART A — PRIMARY OBJECTIVE

## 2. BUILD A REUSABLE RESILIENCE FRAMEWORK

Build a Java 21 framework similar in core purpose to Resilience4j, supporting configurable fault-tolerance policies for
distributed service-to-service calls.

Example:

An Order Service calls a Payment Service.

The Payment Service experiences high latency and intermittent failures.

The framework should:

1. Enforce an overall request deadline.
2. Check the circuit breaker state.
3. Acquire a bulkhead permit.
4. Execute the request.
5. Classify failures.
6. Retry only eligible operations.
7. Apply bounded exponential backoff with jitter.
8. Release permits correctly.
9. Record circuit-breaker outcomes.
10. Open the circuit when failure thresholds are exceeded.
11. Fail fast while the circuit is open.
12. Permit controlled probe requests during HALF_OPEN.
13. Return a safe fallback only when appropriate.
14. Expose metrics and tracing information.
15. Recover automatically when dependency health improves.

### Critical design requirement

**The framework must prevent cascading failures rather than merely retry failed requests.**

It must bound downstream load, protect local resources, and respect a single end-to-end latency budget.

---

# PART B — FRAMEWORK SCOPE AND COMPARISON

## 3. COMPARE ESTABLISHED RESILIENCE PATTERNS

Evaluate:

- Circuit Breaker
- Retry
- Semaphore Bulkhead
- Thread Pool Bulkhead
- Timeout / Time Limiter
- Rate Limiter
- Fallback
- Load Shedding
- Backpressure
- Adaptive Concurrency Limiting
- Health-Aware Routing

For each pattern explain:

1. Problem solved
2. Core algorithm
3. State or resource model
4. Concurrency behavior
5. Failure handling
6. Performance overhead
7. Advantages and disadvantages
8. Suitable production use cases
9. Common misuse
10. Interactions with other resilience patterns

### Architectural choice

Implement Circuit Breaker, Retry, Semaphore Bulkhead, Timeout, and Fallback as mandatory core capabilities.

Implement Thread Pool Bulkhead as a separate module if justified by blocking workloads and isolation requirements.

Treat adaptive concurrency limiting and distributed rate limiting as future extensions unless sufficient requirements
justify implementation.

---

# PART C — HIGH-LEVEL ARCHITECTURE

## 4. COMPONENT DESIGN

Design the framework with logical components such as:

- ResilienceExecutor
- ResiliencePolicy
- CircuitBreaker
- CircuitBreakerRegistry
- RetryPolicy
- RetryExecutor
- Bulkhead
- BulkheadRegistry
- TimeLimiter
- Deadline
- FailureClassifier
- BackoffStrategy
- FallbackHandler
- ResilienceEvent
- ResilienceMetrics
- ResilienceConfiguration
- ResilienceDecorator

Provide Mermaid diagrams for:

1. Framework architecture
2. Resilience call execution
3. Circuit Breaker state transitions
4. Retry lifecycle
5. Bulkhead acquisition
6. Timeout and cancellation
7. Fallback evaluation
8. Policy composition
9. Spring Boot integration
10. Fault recovery

### Separation of concerns

The core library must not depend on:

- Spring Boot
- Spring MVC
- Spring AOP
- Kafka
- Redis
- Cloud provider SDKs

Spring integration should be implemented in separate modules.

---

# PART D — CIRCUIT BREAKER

## 5. IMPLEMENT CIRCUIT BREAKER FROM SCRATCH

Implement a concurrency-safe Circuit Breaker supporting:

- CLOSED
- OPEN
- HALF_OPEN
- DISABLED or FORCED_OPEN administrative states where justified

### State semantics

**CLOSED**

Requests execute normally.

Collect eligible call outcomes.

Open the circuit when configured failure conditions are met.

**OPEN**

Reject requests immediately.

Do not invoke the protected operation.

After the configured open duration, permit transition toward HALF_OPEN.

**HALF_OPEN**

Allow a limited number of probe calls.

Evaluate their outcomes.

Transition to CLOSED on sufficient recovery evidence or OPEN on failure.

### Requirements

- Atomic state transitions
- Configurable thresholds
- Bounded outcome history
- Minimum call count
- Thread-safe counters
- Time-based or count-based sliding windows
- Configurable open duration
- Half-open concurrency limit
- Manual reset
- State transition events
- Metrics

---

## 6. SLIDING WINDOW FAILURE TRACKING

Compare:

1. Count-based sliding window
2. Time-based sliding window
3. Fixed window
4. Ring-buffer implementation
5. Bucketed aggregation

Implement at least one bounded sliding-window algorithm.

Prefer an efficient, thread-safe design suitable for high concurrency.

Track:

- Successful calls
- Failed calls
- Slow calls
- Ignored exceptions
- Rejected calls

Support:

- Failure rate threshold
- Slow-call rate threshold
- Minimum calls before evaluation
- Window size
- Slow-call duration threshold

### Important invariant

The denominator used for failure-rate calculation must match the documented outcome-classification rules.

A rejected call because the circuit is already OPEN should not accidentally be counted as another downstream failure
unless intentionally designed.

---

## 7. HALF-OPEN CONCURRENCY SAFETY

Analyze this scenario:

1. Circuit is OPEN.
2. Open duration elapses.
3. One hundred threads attempt calls simultaneously.
4. Only a configured number of probes may execute.

Implement atomic probe-permit allocation.

Ensure:

- Probe permits are bounded.
- Probe completion is recorded once.
- Cancelled or timed-out probes follow documented classification rules.
- Late probe results from an old generation cannot incorrectly close a newly reopened circuit.

Use state-generation/version tokens where necessary.

---

## 8. CIRCUIT BREAKER THREAD SAFETY

Compare:

- synchronized
- ReentrantLock
- AtomicReference
- CAS
- LongAdder
- Atomic counters

Choose the simplest design that provides correct linearizable state transitions.

Avoid implementing a complex lock-free state machine without demonstrated value.

Define the concurrency and memory-visibility guarantees.

Provide tests for simultaneous state changes and races.

---

# PART E — RETRY FRAMEWORK

## 9. IMPLEMENT RETRY POLICIES

Support:

- Fixed delay
- Exponential backoff
- Full jitter
- Equal jitter, optionally
- Maximum attempts
- Maximum elapsed duration
- Retryable exception classification
- Retryable HTTP status classification
- Retry-After where applicable
- Cancellation-aware backoff

Example:

`baseDelay × 2^(attempt − 1)`

Apply caps and overflow-safe arithmetic.

Use an injectable clock/scheduler for deterministic testing.

---

## 10. RETRY SAFETY

Define which operations are safe to retry.

Examples:

### Typically retryable under suitable contracts

- Idempotent reads
- Transient connection failures before execution
- Operations protected by end-to-end idempotency keys
- Certain HTTP 429/503 responses

### Potentially unsafe

- Payment capture
- Money transfer
- Order creation without idempotency
- External notification sending
- Resource provisioning without operation identity

### Critical scenario

The Payment Service processes a request, but the client times out before receiving the response.

A retry could perform the same financial action twice.

The framework must not automatically retry this operation without a verified idempotency/reconciliation contract.

Define a call-level retry safety policy and clearly expose ambiguous outcomes.

---

## 11. RETRY BUDGET

Implement or design bounded retry budgets.

Address:

- Maximum attempts
- End-to-end deadline
- Per-attempt timeout
- Retry backoff
- Downstream rate limits
- Retry amplification
- Retry storms
- Service-level retry budgets

Explain why retries at multiple microservice layers can multiply downstream requests.

For example, three layers each attempting three calls may produce up to 27 downstream attempts.

Prevent unintentional nested retries through policy ownership and telemetry.

---

# PART F — BULKHEAD

## 12. SEMAPHORE BULKHEAD

Implement a thread-safe semaphore-based bulkhead.

Support:

- Maximum concurrent calls
- Maximum wait duration
- Immediate rejection
- Cancellation-aware acquisition
- Permit release
- Metrics
- Fairness configuration where useful

### Critical invariant

Every successful permit acquisition must release exactly one permit, even when the protected call throws, times out, or
is cancelled.

Test exception and cancellation paths.

Avoid permit leakage and double release.

---

## 13. THREAD POOL BULKHEAD

Evaluate a bounded thread pool bulkhead.

Support where implemented:

- Dedicated executor
- Maximum workers
- Bounded queue
- Rejection policy
- Graceful shutdown
- Queue saturation metrics
- Task cancellation

Compare:

- Platform threads
- Virtual threads
- Bounded executor pools
- Semaphore-based concurrency limits

Explain why one executor per downstream endpoint may become expensive.

Do not create unbounded executors or queues.

---

## 14. BULKHEAD AND VIRTUAL THREADS

Evaluate Java 21 virtual threads.

Explain:

- Virtual threads reduce thread-management overhead for many blocking tasks.
- They do not increase database connection-pool capacity.
- They do not remove downstream concurrency constraints.
- They do not make CPU-intensive calls faster.
- They do not remove memory or request backlog limits.
- Some blocking operations may pin carrier threads.

Use semaphore bulkheads to bound downstream concurrency regardless of the threading model.

Provide tests running the framework on platform threads and virtual threads.

---

# PART G — TIMEOUTS AND DEADLINES

## 15. TIME LIMITER

Implement support for:

- Per-attempt timeout
- End-to-end deadline
- Cancellation
- Timeout classification
- Timeout metrics
- Propagation of remaining time budget

Use monotonic elapsed-time measurement where appropriate.

### Critical distinction

Completing a Future exceptionally after a timeout does not guarantee the underlying network operation has stopped.

Define behavior for:

- Cooperative cancellation
- HTTP request cancellation
- Interruptible blocking calls
- Non-interruptible operations
- Late results
- Resource cleanup

Do not claim timeout enforcement kills arbitrary Java code.

---

## 16. DEADLINE PROPAGATION

Design an explicit deadline budget.

Example:

Order Service has a 2-second request deadline.

The framework must allocate time among:

- Bulkhead wait
- Circuit check
- Network attempt
- Retry delay
- Additional attempts
- Fallback

Never allow each retry to receive a fresh unlimited 2-second timeout.

Use one shared end-to-end budget.

Document deadline propagation across microservices and tracing contexts.

---

# PART H — FALLBACK

## 17. FALLBACK FRAMEWORK

Implement configurable fallback support.

Examples:

- Return cached product details.
- Return stale but permitted recommendations.
- Return a default noncritical feature configuration.
- Return a controlled service-unavailable response.

### Important restrictions

Do not provide misleading fallback results for:

- Payment success
- Financial balances
- Inventory ownership
- Authentication
- Authorization
- Confirmed order state

A fallback must not silently convert a failed financial operation into an apparent success.

Define fallback eligibility based on exception type and business context.

---

# PART I — RESILIENCE POLICY COMPOSITION

## 18. DESIGN COMPOSITION ORDER

Compare compositions such as:

`Fallback → CircuitBreaker → Retry → Bulkhead → Timeout`

and:

`Fallback → Retry → CircuitBreaker → Bulkhead → Timeout`

Explain the different accounting and failure-classification semantics.

Do not assume one composition order is universally correct.

### Recommended baseline

Use a documented composition where:

- Overall deadline bounds the entire operation.
- Circuit breaker controls admission to the dependency.
- Bulkhead bounds concurrent downstream work.
- Retry controls individual attempts.
- Per-attempt timeouts limit each attempt.
- Fallback executes only after eligible failure.

Explicitly define whether the circuit breaker records each retry attempt or the final logical operation outcome.

Choose a default and allow focused composition overrides where justified.

---

## 19. EXECUTION API

Expose a programmatic API.

Conceptually:

```java
ResilienceExecutor executor = ...;

OrderResponse response = executor.execute(
    "payment-service",
    () -> paymentClient.authorize(request)
);
```

Provide:

- Synchronous execution
- `CompletionStage` / `CompletableFuture` support
- Deadline support
- Cancellation behavior
- Typed result handling
- Exception propagation
- Fallback composition

Ensure APIs are type-safe and do not unnecessarily wrap exceptions.

---

# PART J — SPRING BOOT INTEGRATION

## 20. SPRING BOOT STARTER

Implement a Spring Boot starter module.

Support:

- Auto-configuration
- Configuration properties
- Bean registration
- Named resilience policies
- Dependency-specific registries
- Metrics integration
- Actuator integration
- Configuration validation

Use verified Spring Boot auto-configuration conventions.

Do not assume historical Spring Boot configuration metadata or starter APIs remain unchanged.

---

## 21. ANNOTATION SUPPORT

Evaluate annotations such as:

- `@CircuitBreaker`
- `@Retry`
- `@Bulkhead`
- `@TimeLimiter`

Implement annotation-based support only after the programmatic API is correct.

Address:

- Spring AOP proxy behavior
- Self-invocation
- Nonpublic methods
- Final methods/classes
- Async return types
- Exception propagation
- Proxy ordering

Document limitations clearly.

Do not claim an annotation protects self-invoked methods when proxy interception does not occur.

---

# PART K — CONFIGURATION

## 22. CONFIGURATION MODEL

Example YAML:

```yaml id="6fwcgv"
resilience:
  instances:
    payment-service:
      circuit-breaker:
        sliding-window-size: 100
        minimum-number-of-calls: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-half-open-calls: 5
      retry:
        max-attempts: 3
        initial-delay: 100ms
        max-delay: 1s
        jitter: true
      bulkhead:
        max-concurrent-calls: 50
        max-wait-duration: 0ms
      timeout:
        per-attempt: 500ms
        overall: 2s
```

Treat this as an illustrative configuration.

Validate:

- Negative durations
- Zero or excessive concurrency
- Impossible thresholds
- Retry count
- Backoff overflow
- Invalid window sizes
- Incompatible policy combinations

Define safe defaults.

---

# PART L — DYNAMIC CONFIGURATION

## 23. RUNTIME POLICY UPDATES

Evaluate dynamic configuration through:

- Spring configuration refresh
- Database-backed configuration
- Config service
- Kubernetes ConfigMaps
- Feature flags

Implement safe atomic policy updates if justified.

Address:

- Configuration versions
- Validation
- Rollback
- In-flight operations
- Registry lifecycle
- Metrics continuity
- Thread safety

Do not mutate partially initialized policy objects in place.

Prefer immutable configuration snapshots.

---

# PART M — DISTRIBUTED RESILIENCE SEMANTICS

## 24. LOCAL VS GLOBAL CIRCUIT BREAKERS

Explain the difference between:

- Per-instance Circuit Breaker
- Per-service Circuit Breaker
- Distributed/global Circuit Breaker

### Default recommendation

Use local Circuit Breakers.

They avoid adding network coordination to the critical call path.

Evaluate sharing dependency-health signals through metrics or service discovery as an optional capability.

Do not assume a centralized global circuit breaker always improves reliability.

---

## 25. SERVICE-LEVEL FAILURE ISOLATION

Design policy identity carefully.

Examples:

- payment-service
- payment-service.authorize
- inventory-service.reserve
- recommendation-service.fetch

Avoid sharing one Circuit Breaker across unrelated dependencies or operations with different reliability
characteristics.

Avoid creating unbounded policy instances per user, request, or arbitrary dynamic key.

---

# PART N — OBSERVABILITY

## 26. METRICS

Expose Micrometer metrics for:

- Circuit breaker state
- Successful calls
- Failed calls
- Slow calls
- Rejected calls
- State transitions
- Retry attempts
- Retry exhaustion
- Retry delays
- Bulkhead active calls
- Bulkhead available permits
- Bulkhead rejections
- Thread pool queue depth
- Timeout count
- Fallback count
- End-to-end latency

Avoid high-cardinality request IDs as metric labels.

---

## 27. TRACING AND LOGGING

Integrate OpenTelemetry.

Capture:

- Dependency identity
- Logical operation
- Attempt count
- Retry decision
- Circuit breaker state
- Bulkhead wait
- Timeout classification
- Final outcome

Avoid one full trace span for every high-frequency internal counter update.

Do not log secrets, tokens, sensitive payloads, or unnecessary customer data.

---

# PART O — FAILURE SCENARIOS

## 28. ANALYZE THESE 35 SCENARIOS

1. Dependency fails for 50% of calls.
2. Dependency becomes extremely slow.
3. Circuit opens while 100 requests are in flight.
4. Many threads transition OPEN to HALF_OPEN simultaneously.
5. Half-open probes complete out of order.
6. Half-open probe is cancelled.
7. Circuit closes while an old failure result arrives.
8. Sliding-window counters race.
9. Failure classification ignores certain exceptions.
10. Dependency returns HTTP 429.
11. Dependency returns HTTP 503 with Retry-After.
12. Network times out after downstream execution.
13. Retrying creates duplicate payment operations.
14. Three microservice layers apply retries.
15. Retry backoff exceeds request deadline.
16. Retry storm occurs after recovery.
17. Bulkhead is saturated.
18. Bulkhead permit leaks after exception.
19. Bulkhead acquisition is cancelled.
20. Thread pool queue becomes full.
21. Virtual threads overwhelm a database pool.
22. Timeout occurs but network operation continues.
23. Fallback returns stale unauthorized data.
24. Configuration changes during in-flight calls.
25. Circuit state changes during configuration refresh.
26. Spring AOP self-invocation bypasses resilience advice.
27. CompletableFuture completes after timeout.
28. Executor shuts down during an operation.
29. Dependency partially recovers.
30. Circuit oscillates between OPEN and CLOSED.
31. Monitoring system becomes unavailable.
32. Kubernetes pod restarts with cold Circuit Breaker state.
33. Network partition isolates the dependency.
34. Rolling deployment uses different resilience configurations.
35. One high-volume endpoint monopolizes shared bulkhead capacity.

For each provide:

- Failure mechanism
- Correctness impact
- Performance impact
- User-visible behavior
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART P — TESTING

## 29. UNIT TESTS

Cover:

- Circuit state transitions
- Sliding-window calculations
- Minimum call threshold
- Slow-call classification
- Half-open permit limits
- CAS/state race behavior
- Retry classification
- Exponential backoff
- Jitter boundaries
- Deadline exhaustion
- Bulkhead permit release
- Cancellation
- Fallback eligibility

Use an injectable clock/scheduler for deterministic tests.

Avoid depending on long real sleeps.

---

## 30. CONCURRENCY TESTS

Verify:

- Thousands of concurrent Circuit Breaker calls
- Concurrent OPEN/HALF_OPEN transitions
- Probe permit correctness
- Sliding-window aggregation
- Bulkhead maximum concurrency
- Permit release under exceptions
- Retry cancellation
- Executor shutdown races
- Virtual-thread compatibility

Use deterministic concurrency tools where possible.

---

## 31. INTEGRATION TESTS

Create at least three runnable Spring Boot services:

- API Gateway / Demo Client
- Order Service
- Payment Service Simulator

The Payment Simulator must support configurable:

- Failure percentage
- Response delay
- HTTP errors
- Connection drops
- Recovery
- Rate-limit responses

Verify resilience behavior through real HTTP requests.

Use Testcontainers where dependencies justify it.

---

## 32. SPRING AOP TESTS

Verify:

- Annotation interception
- Self-invocation limitation
- Method visibility behavior
- Exception propagation
- Async methods
- Policy naming
- Configuration binding
- Auto-configuration

Ensure the Spring Boot starter can be consumed by an independent sample application.

---

## 33. FAULT INJECTION TESTS

Simulate:

- HTTP 500
- HTTP 503
- HTTP 429
- Connection refused
- DNS failure
- Socket timeout
- Slow response
- Partial response
- Thread pool exhaustion
- Connection pool saturation
- Downstream restart
- Network interruption

Verify that the framework fails predictably and does not create unbounded retry amplification.

---

# PART Q — PERFORMANCE ENGINEERING

## 34. FRAMEWORK BENCHMARKS

Use JMH for isolated hot-path benchmarks.

Measure:

- Circuit Breaker admission overhead
- Sliding-window recording overhead
- Retry policy evaluation
- Bulkhead acquisition/release
- Deadline checks
- Allocation rate
- Lock contention
- Throughput under concurrency

Benchmark both:

- Normal successful execution
- Failure-heavy workloads

Use JMH correctly, including warmup, measurement, forks, and appropriate result consumption.

---

## 35. END-TO-END BENCHMARKS

Use k6, Gatling, or a custom load harness.

Test:

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS as a scale target

Evaluate:

- No resilience layer
- Circuit Breaker only
- Retry only
- Bulkhead only
- Combined policies
- Slow downstream
- Recovering downstream
- Saturated downstream

Measure:

- Offered and achieved RPS
- p50/p95/p99 latency
- Error rate
- Rejection rate
- Retry amplification
- Downstream call count
- Bulkhead saturation
- Recovery time
- CPU/heap/GC
- Thread contention
- Allocation rate

Never fabricate benchmark results.

Do not claim zero overhead or guaranteed latency improvements.

---

# PART R — COMPARISON WITH RESILIENCE4J

## 36. REFERENCE IMPLEMENTATION COMPARISON

Use Resilience4j as a benchmark and functional reference.

Compare:

- Circuit Breaker state semantics
- Sliding-window algorithms
- Retry configuration
- Semaphore Bulkhead
- Thread Pool Bulkhead
- TimeLimiter
- Spring Boot integration
- Metrics
- Performance
- Operational maturity

Create benchmark scenarios comparing the custom framework against compatible Resilience4j modules.

Do not simply reproduce Resilience4j source code.

Use original implementation and properly documented algorithms.

### Required review

Explain why an enterprise might prefer Resilience4j over a custom framework for production systems.

A custom implementation must demonstrate a concrete maintenance, integration, performance, or educational benefit before
being recommended over a mature library.

---

# PART S — TECHNOLOGY STACK

## 37. JAVA 21 + SPRING BOOT STACK

| Layer              | Technology                                    |
|--------------------|-----------------------------------------------|
| Language           | Java 21                                       |
| Framework          | Spring Boot, verified compatible release      |
| Build              | Maven                                         |
| Core algorithms    | Java concurrency utilities                    |
| Synchronous APIs   | Java functional interfaces                    |
| Async APIs         | CompletionStage/CompletableFuture             |
| Spring integration | Auto-configuration and Spring AOP             |
| HTTP clients       | Spring RestClient/WebClient where appropriate |
| Observability      | Micrometer/OpenTelemetry                      |
| Unit tests         | JUnit 5/Mockito                               |
| Concurrency tests  | JUnit + deterministic test utilities          |
| Integration tests  | Testcontainers where needed                   |
| Reference library  | Resilience4j                                  |
| Benchmarks         | JMH/k6/Gatling                                |
| Containers         | Docker                                        |
| Deployment         | Kubernetes                                    |
| Monitoring         | Prometheus/Grafana                            |

Verify exact dependency versions and compatibility against official documentation.

---

# PART T — PROJECT STRUCTURE

## 38. MULTI-MODULE MAVEN PROJECT

Create a reusable Maven project.

```text id="9lv2fd"
enterprise-resilience-framework/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── circuit-breaker.md
│   ├── retry.md
│   ├── bulkhead.md
│   ├── deadlines.md
│   ├── composition.md
│   ├── concurrency.md
│   ├── performance.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── resilience-core/
├── resilience-circuitbreaker/
├── resilience-retry/
├── resilience-bulkhead/
├── resilience-timelimiter/
├── resilience-spring-boot-starter/
├── resilience-micrometer/
├── resilience-benchmarks/
├── examples/
│   ├── order-service/
│   ├── payment-service-simulator/
│   └── demo-client/
├── integration-tests/
├── docker/
├── helm/
└── .github/workflows/
```

Keep the framework core independent of Spring and domain-specific applications.

Avoid cyclic dependencies.

---

# PART U — SECURITY

## 39. SECURITY REQUIREMENTS

Implement or document:

- Safe configuration validation
- Bounded resource consumption
- No arbitrary code execution from configuration
- Secure administrative endpoints
- Authentication and authorization for configuration changes
- Secret-safe logging
- Rate-limit management
- Dependency identity validation
- Safe fallback behavior
- Avoidance of sensitive data leakage through metrics

Do not allow untrusted callers to dynamically create unlimited resilience policy instances.

---

# PART V — DEPLOYMENT

## 40. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfiles
- Docker Compose
- Example microservices
- Fault-injection controls
- Kubernetes Deployments
- Services
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployment
- CI/CD

Ensure local deployment demonstrates normal operation, downstream failure, circuit opening, and recovery.

---

# PART W — OBSERVABILITY

## 41. PROMETHEUS AND GRAFANA

Provide dashboards displaying:

- Circuit Breaker state
- Failure rate
- Slow-call rate
- Open/half-open transitions
- Retry attempts
- Retry exhaustion
- Bulkhead utilization
- Bulkhead rejections
- Timeout count
- Fallback count
- Latency percentiles
- Downstream saturation

Define alerts for:

- Circuit continuously open
- Excessive retry amplification
- Bulkhead saturation
- High timeout rate
- Slow dependency recovery
- Fallback overuse

Avoid alert storms caused by high-cardinality policy instances.

---

# PART X — IMPLEMENTATION WORKFLOW

## 42. DEVELOPMENT PHASES

Execute incrementally.

### Phase 1 — Architecture and Contracts

Define API semantics, composition rules, state machines, concurrency guarantees, and failure classification.

### Phase 2 — Circuit Breaker Core

Implement state transitions, sliding windows, half-open probes, registry, and deterministic tests.

### Phase 3 — Retry Engine

Implement retry policies, backoff, jitter, failure classifiers, retry budgets, and cancellation.

### Phase 4 — Bulkhead

Implement semaphore isolation, bounded waits, permit safety, and concurrency tests.

### Phase 5 — Time Limiter and Deadlines

Implement overall deadlines, per-attempt timeouts, cancellation semantics, and tests.

### Phase 6 — Policy Composition

Implement synchronous/async decorators, composition order, fallback, and operation-level metadata.

### Phase 7 — Spring Boot Integration

Implement starter, auto-configuration, properties, registries, Actuator, and optional annotations.

### Phase 8 — Observability

Implement Micrometer metrics, OpenTelemetry integration, structured events, and dashboards.

### Phase 9 — Example Microservices

Implement Order Service, Payment Simulator, fault injection, and demonstration scenarios.

### Phase 10 — Reliability Testing

Run concurrency, integration, cancellation, timeout, and fault-injection tests.

### Phase 11 — Resilience4j Comparison

Benchmark and compare behavior, performance, API usability, and operational complexity.

### Phase 12 — Deployment

Implement Docker Compose, Kubernetes/Helm, CI/CD, and runbooks.

### Phase 13 — Performance Optimization

Measure hot-path overhead, contention, latency, and memory allocation. Optimize only demonstrated bottlenecks.

### Phase 14 — Final Architecture Review

Review correctness, failure safety, resource isolation, concurrency, compatibility, and operational risks.

For each phase:

- Implement actual source files.
- Keep the project buildable.
- Run relevant tests.
- Fix discovered defects.
- Preserve unrelated work.
- Document significant decisions.
- Distinguish verified behavior from untested assumptions.

If repository and execution tools are available, implement directly.

Otherwise, provide complete source files and runnable commands without claiming execution.

---

# PART Y — REQUIRED DELIVERABLES

## 43. COMPLETE PROJECT OUTPUTS

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Correctness invariants
- Public API contracts
- Acceptance criteria

### Deliverable 2 — HLD

- Framework architecture
- Component boundaries
- Policy composition
- State machines
- Concurrency model
- Deployment architecture

### Deliverable 3 — LLD

- UML class diagrams
- Sequence diagrams
- Java interfaces
- State transition logic
- Retry algorithms
- Bulkhead algorithms
- Deadline propagation

### Deliverable 4 — Framework Source

- Java 21 core
- Circuit Breaker
- Retry
- Bulkhead
- Time Limiter
- Fallback
- ResilienceExecutor
- Registries
- Configuration
- Spring Boot starter
- Metrics integration

### Deliverable 5 — Example Microservices

- Order Service
- Payment Simulator
- Demo Client
- Fault injection

### Deliverable 6 — Automated Tests

- Unit tests
- Concurrency tests
- Integration tests
- Spring AOP tests
- Failure tests
- Security tests

### Deliverable 7 — Performance

- JMH benchmarks
- End-to-end load tests
- Resilience4j comparisons
- Actual results where measured
- Bottleneck analysis

### Deliverable 8 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- Circuit Breaker guide
- Retry safety guide
- Bulkhead guide
- Timeout semantics
- Composition guide
- Spring Boot integration
- Operational runbook
- Known limitations

---

# PART Z — MANDATORY ARCHITECTURE REVIEW

## 44. ANSWER THESE 40 QUESTIONS

1. What problem does a Circuit Breaker solve?
2. What are CLOSED, OPEN, and HALF_OPEN states?
3. How do you implement atomic Circuit Breaker transitions?
4. How does count-based sliding window work?
5. How does time-based sliding window work?
6. Why is minimum call count necessary?
7. How should slow calls affect Circuit Breaker decisions?
8. How do you limit HALF_OPEN probes?
9. How do you handle late probe results?
10. How do you prevent Circuit Breaker state races?
11. What is exponential backoff?
12. Why is jitter important?
13. What is retry amplification?
14. How do nested retries create exponential load?
15. When should a request not be retried?
16. Why are payment operations especially sensitive to retry?
17. How do you implement retry budgets?
18. How should Retry-After be handled?
19. What is a semaphore bulkhead?
20. What is a thread pool bulkhead?
21. When should each bulkhead type be used?
22. How do you prevent permit leaks?
23. How do virtual threads affect bulkhead design?
24. Why do virtual threads not eliminate downstream capacity limits?
25. What is the difference between timeout and cancellation?
26. Why might a timed-out operation continue executing?
27. How do you propagate deadlines across services?
28. What is the correct resilience composition order?
29. Should Circuit Breaker count individual retry attempts or the logical call?
30. When is fallback safe?
31. Why is fallback dangerous for financial operations?
32. How should Circuit Breakers be scoped?
33. Should Circuit Breaker state be distributed?
34. How does Spring AOP affect annotation-based resilience?
35. How do you test Circuit Breaker transitions deterministically?
36. How do you benchmark a resilience framework correctly?
37. What are Resilience4j's architectural advantages?
38. What is the operational cost of maintaining a custom framework?
39. What guarantees can a custom framework realistically provide?
40. What evidence is required before production adoption?

For every question explain:

- Architecture
- Concurrency correctness
- Failure behavior
- Performance implications
- Production trade-offs

---

# DEFINITION OF DONE

## 45. ACCEPTANCE CRITERIA

The project is complete only when:

- Java 21 project builds successfully.
- Core framework has no Spring dependency.
- Circuit Breaker transitions correctly.
- Sliding-window metrics are accurate under concurrency.
- Half-open probe limits are enforced.
- Old probe results cannot corrupt newer states.
- Retry policies implement bounded attempts and delays.
- Retry exhaustion is handled.
- Retry safety policy prevents unsafe automatic retries.
- Bulkhead concurrency limits are enforced.
- Permits are released correctly.
- Timeouts and deadlines behave according to documented semantics.
- Cancellation behavior is tested.
- Fallback is only applied according to explicit policy.
- Policy composition is deterministic.
- Synchronous and asynchronous APIs function.
- Spring Boot starter auto-configuration works.
- Annotation limitations are documented and tested where implemented.
- Micrometer metrics are exposed.
- Example microservices demonstrate failure isolation.
- Automated tests cover concurrency, failures, and cancellation.
- Docker Compose supports a functional demonstration.
- Kubernetes manifests are provided.
- JMH benchmarks are reproducible.
- Resilience4j comparison is documented.
- Performance claims are measured or marked unverified.
- Remaining limitations and operational risks are documented.

Do not declare production readiness based solely on unit tests or a successful demonstration.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, reusable, enterprise-oriented Circuit Breaker, Retry, Bulkhead, Timeout, and Fallback Framework using
Java 21 and Spring Boot integration**.

Prioritize:

1. **Correctness:** Thread-safe state machines, bounded counters, and valid transitions.
2. **Failure isolation:** Prevent cascading failures and downstream overload.
3. **Retry safety:** Avoid duplicate side effects and retry storms.
4. **Resource protection:** Bounded concurrency, queues, and deadlines.
5. **Cancellation semantics:** Avoid pretending timed-out operations have stopped.
6. **Maintainability:** Minimal dependencies and clean framework interfaces.
7. **Spring integration:** Safe auto-configuration and predictable annotation behavior.
8. **Performance:** Low-overhead hot paths verified by benchmarks.
9. **Observability:** Metrics and traces for diagnosing resilience behavior.
10. **Reproducibility:** Complete source, tests, demo services, benchmarks, and infrastructure.

Begin with a concise architecture assessment and define the public framework API.

Then implement the complete system phase by phase.

Use available development tools to create files, compile, run tests, benchmark, and fix defects.

At completion report:

- Implemented features
- Architecture decisions
- Public APIs
- Concurrency guarantees
- Source structure
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Resilience4j comparison
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Resilience Framework with core algorithms, Spring Boot
starter, example microservices, automated tests, benchmarks, and deployment infrastructure.**