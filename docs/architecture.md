# Architecture and low-level design

Modules form a DAG. Core is dependency-free; executor composes the algorithm modules; observability and Spring are
outward adapters. Separate example JVMs use a read-only quote contract. No distributed coordination enters the
resilience call path.

## Framework architecture

```mermaid
flowchart TD
 Application --> Starter[Spring Boot starter]
 Starter --> Metrics[Micrometer / OTel]
 Starter --> Budget[Local / Redis retry budget]
 Budget --> Redis[Bounded Redis adapter]
 Starter --> Propagation[Servlet deadline / trace filter]
 Propagation --> HttpAdapter[HTTP deadline / W3C injection]
 Starter --> Executor
 Metrics --> Executor
 Executor --> TaskContext[Scoped deadline / approved context]
 Executor --> Budget
 Executor --> Core[Core contracts]
 Executor --> CircuitBreaker
 Executor --> RetryPolicy
 Executor --> Bulkhead
 Executor --> TimeLimiter
 CircuitBreaker --> Core
 RetryPolicy --> Core
 Bulkhead --> Core
 TimeLimiter --> Core
```

## Call execution

```mermaid
sequenceDiagram
 participant Caller
 participant Executor
 participant Circuit
 participant Worker
 participant Dependency
 Caller->>Executor: execute(policy, safety, action)
 Note over Executor: Additional attempts reserve a shared budget token
 Executor->>Circuit: acquire ticket
 Executor->>Worker: submit with bounded admission
 Worker->>Worker: acquire semaphore
 Worker->>Dependency: action()
 Dependency-->>Worker: outcome
 Worker->>Worker: release permit on actual exit
 Worker-->>Executor: outcome or timed wait expiry
 Executor->>Circuit: complete exactly once
 Executor-->>Caller: result / exception / eligible fallback
```

## Circuit states

```mermaid
stateDiagram-v2
 [*] --> CLOSED
 CLOSED --> OPEN: eligible failure or slow threshold
 OPEN --> HALF_OPEN: elapsed open duration + admission
 HALF_OPEN --> CLOSED: all required probes succeed
 HALF_OPEN --> OPEN: failed or slow probe
 CLOSED --> FORCED_OPEN: admin
 FORCED_OPEN --> CLOSED: reset
 CLOSED --> DISABLED: admin
 DISABLED --> CLOSED: reset
```

## Retry lifecycle

```mermaid
flowchart TD
 Start --> Attempt
 Attempt --> Success{Success?}
 Success -->|yes| Return
 Success -->|no| Eligible{Safe and retryable?}
 Eligible -->|no| Throw
 Eligible -->|yes| Budget{Attempts and time remain?}
 Budget -->|no| Throw
 Budget -->|yes| Delay[Bounded full jitter / Retry-After]
 Delay --> Cancel{Interrupted?}
 Cancel -->|yes| Throw
 Cancel -->|no| Attempt
```

## Bulkhead acquisition

```mermaid
flowchart TD
 Admission --> Try[Interruptible timed semaphore acquire]
 Try --> Acquired{Permit?}
 Acquired -->|no| Reject
 Acquired -->|yes| Action
 Action --> Exit[Actual worker exit]
 Exit --> Release[Idempotent release]
```

## Timeout and cancellation

```mermaid
sequenceDiagram
 participant Caller
 participant Coordinator
 participant Worker
 Caller->>Coordinator: executeAsync
 Coordinator->>Worker: submit
 Worker->>Worker: retain permit
 Coordinator->>Worker: cancel(true) on timeout
 Coordinator-->>Caller: TimeoutException
 Note over Worker: arbitrary code may ignore interruption
 Worker->>Worker: actual exit releases permit
 Note over Coordinator: late result never records twice
```

## Fallback evaluation

```mermaid
flowchart TD
 Failure --> Interrupt{Cancellation / interruption?}
 Interrupt -->|yes| Propagate
 Interrupt -->|no| Eligible{Explicit eligible business fallback?}
 Eligible -->|no| Propagate
 Eligible -->|yes| Budget{Deadline remains?}
 Budget -->|no| Propagate
 Budget -->|yes| Fallback[Bounded worker fallback]
 Fallback --> Result[Result without circuit success]
```

## Composition

```mermaid
flowchart LR
 Deadline --> FallbackBoundary --> Retry --> CircuitAdmission --> Bulkhead --> AttemptTimeout --> Action
```

## Spring Boot integration

```mermaid
flowchart TD
 Imports[AutoConfiguration.imports] --> AutoConfig
 YAML --> Properties
 Properties --> Validation
 Validation --> Registry
 AutoConfig --> Registry
 AutoConfig --> Executor
 Registry --> Executor
 Proxy[Public synchronous proxy] --> Aspect --> Executor
 Executor --> Micrometer
 Executor --> ExistingSpan[Existing OTel span attributes]
```

## Recovery

```mermaid
sequenceDiagram
 participant Calls
 participant Circuit
 participant Dependency
 Calls->>Circuit: failures / slowness
 Circuit->>Circuit: OPEN
 Calls->>Circuit: reject while open
 Calls->>Circuit: admission after wait
 Circuit->>Circuit: HALF_OPEN
 Circuit->>Dependency: bounded probes
 Dependency-->>Circuit: successful probes
 Circuit->>Circuit: CLOSED fresh generation
```

## Class relationships

```mermaid
classDiagram
 ResilienceExecutor --> PolicyRegistry
 PolicyRegistry --> PolicyConfig
 PolicyRegistry --> CircuitBreaker
 PolicyRegistry --> Bulkhead
 ResilienceExecutor --> TimeLimiter
 ResilienceExecutor --> FailureClassifier
 ResilienceExecutor --> Fallback
 PolicyConfig *-- CircuitConfig
 PolicyConfig *-- RetryPolicy
 CircuitBreaker *-- Ticket
 Bulkhead *-- Permit
```

## Deployment

```mermaid
flowchart LR
 User --> Gateway[Demo client :8080]
 Gateway --> Order[Order :8081]
 Order --> Payment[Payment simulator :8082]
 Prometheus --> Gateway
 Prometheus --> Order
 Prometheus --> Payment
 Grafana --> Prometheus
```
