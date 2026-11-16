# Enterprise resilience framework

Java 21 multi-module framework and three Spring Boot 3.5.16 demo services. Includes Redis-backed fleet retry budgets and
automatic deadline/MDC/OpenTelemetry propagation across supported execution and HTTP boundaries. This is a reviewable
custom implementation, **not certified production-ready**. See [verification](docs/verification.md) for executed checks
and gaps.

## Build and run

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; set JDK 21 path on Linux
mvn -B verify
export DEMO_ADMIN_TOKEN=$(openssl rand -hex 24)
export GRAFANA_PASSWORD=$(openssl rand -hex 24)
export REDIS_PASSWORD=$(openssl rand -hex 24)
docker compose -f docker/compose.yml up --build
sh scripts/demo.sh
# Grafana http://localhost:3000; Prometheus http://localhost:9090
```

Run independent JVMs with
`java -jar examples/payment-service-simulator/target/payment-service-simulator-1.0.0-SNAPSHOT.jar`, then the equivalent
order-service and demo-client jars. Use `python3 scripts/verify-demo.py` for an isolated-port packaged demonstration.
Simulator requires `DEMO_ADMIN_TOKEN`. Standalone ports: simulator 8082, order 8081, gateway 8080. Compose publishes
18082, 18081 and 18080 to avoid common local port conflicts. `GET /demo` traverses all three. Fault controls:
`POST /fault`, JSON fields `failurePercent`, `delayMillis`, `status`, `partial`; token header `X-Admin-Token`. Never
expose this simulator publicly.

## Public API

```java
var registry = new PolicyRegistry(100, Ticker.SYSTEM);
registry.update("payment-service.quote", PolicyConfig.defaults());
try (var executor = new ResilienceExecutor(registry, 100, 100, EventSink.NOOP)) {
    String quote = executor.execute("payment-service.quote", RetrySafety.IDEMPOTENT,
                                   () -> paymentClient.getQuote());
    CompletableFuture<String> async = executor.executeAsync("payment-service.quote",
            RetrySafety.IDEMPOTENT, () -> paymentClient.getQuote());
    // executeStage adapts a CompletionStage-producing client and propagates cancellation.
}
```

The snippet assumes an application-supplied paymentClient. Production applications must register a singleton executor
and close it during shutdown. `execute` is blocking; `executeAsync` offloads the entire call to a bounded coordinator.
Async cancellation interrupts that coordinator and cancels its active task. No guarantee is made that user or network
code obeys cancellation. Never block an event loop with `execute`.

Immutable contracts: CircuitConfig, RetryPolicy, PolicyConfig. Algorithms: CircuitBreaker, Bulkhead, TimeLimiter,
Deadline. Integration: PolicyRegistry, ResilienceExecutor, Fallback<T>, FailureClassifier, EventSink. Default exception
policy counts IOExceptions and Timeouts; business/runtime exceptions are ignored. Custom classifiers may override this
explicitly. No automatic HTTP parsing in core: adapters must translate eligible statuses to RetryAfterException or
IOException.

## Spring consumer

Depend on `dev.resilience:resilience-spring-boot-starter:1.0.0-SNAPSHOT` after `mvn install`. Auto-configuration uses
Boot's AutoConfiguration.imports. Inject ResilienceExecutor or annotate a public proxy-invoked synchronous method with
`@Resilient(value="payment-service.quote", safety=RetrySafety.IDEMPOTENT)`.

```yaml
resilience:
  workers: 100
  async-calls: 100
  instances:
    "[payment-service.quote]":
      concurrency: 50
      bulkhead-wait: 0ms
      max-attempts: 3
      initial-delay: 50ms
      max-delay: 1s
      per-attempt: 500ms
      overall: 2s
      window-size: 100
      minimum-calls: 20
      failure-rate: 50
      slow-rate: 100
      slow-duration: 400ms
      open-duration: 30s
      probes: 5
```

Bracketed YAML keys preserve dots in policy identities during Spring binding. This deliberately uses a flat instance
schema; it does not bind the illustrative nested schema in the task. Invalid values fail startup. Only preconfigured
policy identities are admitted. API annotations reject async methods before invocation; use executeStage.
Self-invocation, final methods/classes and nonpublic methods must not be relied on for interception. The starter
propagates deadline, MDC and OpenTelemetry Context across framework workers. Security, transaction and arbitrary
thread-local state require an explicitly approved integration. Use the supplied ResilienceHttpClient for automatic
outbound deadline/trace headers.

## Documentation

[Architecture](docs/architecture.md), [requirements](docs/requirements.md), [circuit breaker](docs/circuit-breaker.md), [retry safety](docs/retry.md), [bulkheads](docs/bulkhead.md), [deadlines](docs/deadlines.md), [composition](docs/composition.md), [concurrency](docs/concurrency.md), [patterns](docs/patterns.md), [35 scenarios](docs/failure-scenarios.md), [40 review answers](docs/architecture-review.md), [security](docs/security.md), [runbook](docs/runbook.md), [performance](docs/performance.md), [limitations](docs/limitations.md).

## Reliability extensions

[Shared fleet retry budget](docs/fleet-retry-budget.md), [automatic context/deadline propagation](docs/context-propagation.md), [chaos/soak and Kubernetes validation](docs/reliability-testing.md), [latest execution record](docs/remaining-work-verification.md).

```sh
sh scripts/verify-reliability.sh                 # Docker Redis + all tests + 60s soak
SOAK_SECONDS=3600 sh scripts/verify-reliability.sh # optional longer release gate
python3 scripts/validate-kubernetes.py --context orbstack
```

Kubernetes validation requires prebuilt `resilience/<service>:validation-v2` images available to the cluster; see the
testing guide. It creates and deletes its own namespace. It does not install metrics-server or modify other workloads.
Compose now requires `REDIS_PASSWORD` as well as the existing demo/Grafana credentials and enables shared retry budgets
for the application services.

## Latest release-gate results

[Actual HA, capacity, CNI and HPA measurements](docs/release-gates.md): metrics-server is installed locally, CNI
enforcement and initial HPA scale-up/down were exercised; a later HPA repeat failed during metrics unavailability, and
the Redis adapter now supports Sentinel discovery. **Production release remains on hold:** strict Redis token durability
failed under an asynchronous replication partition, quorum recovery took 47 seconds, and the tested 1k/10k/100k-RPS
healthy targets failed. Only the 100-RPS control met this harness's healthy thresholds.

```sh
sh scripts/install-local-metrics-server.sh   # local ARM64 OrbStack fallback
python3 scripts/validate-network-policy.py --context orbstack
python3 scripts/validate-hpa.py
mvn -Pha-probe -DskipTests package
python3 scripts/validate-redis-ha.py
python3 scripts/validate-release-load.py
```

HA/network/HPA tests remove only their own resources. metrics-server and the existing Compose demo remain running. High
offered-load tests deliberately bound generator resources and may fail; offered RPS must not be confused with achieved
or successful throughput.
