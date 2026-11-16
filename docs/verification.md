# Initial implementation verification record

This is the historical first implementation snapshot.
See [latest remaining-work verification](remaining-work-verification.md) for the subsequent 62-test build, shared retry
budget, propagation, soak, and live Kubernetes results.

Executed on 2026-11-15 with Oracle Java 21.0.13.0.3, Maven 3.10.0, macOS aarch64. Maven's default JDK was 27; all
reported builds explicitly set JAVA_HOME to JDK 21. Build used a writable copy of the local Maven cache at
/tmp/resilience-m2 because the original cache was read-only in the execution sandbox.

## Passed

- Full 13-module reactor `mvn verify` on Java 21: **33 tests, zero
  failures/errors/skips**. [Per-suite summary](results/junit-summary.txt). Includes 5,000 concurrent circuit completions
  under both platform and virtual threads, 100-way half-open contention, ignored probe replacement, stale generation
  results, timeout permit retention, cancellation, retry safety/budgets, caller deadlines, fallback, configuration/AOP
  and real HTTP faults/recovery.
- Three independently packaged Spring Boot JVMs: `scripts/verify-demo.py` passed healthy chain, injected 500s, circuit
  OPEN, no additional downstream calls during rejection, recovery and Prometheus output. Harness uses dynamic loopback
  ports; fixed local ports collided with an unrelated existing container during diagnosis. HTTP clients are warmed
  before fault assertions.
- Docker multi-stage build and three-service Compose demonstration: `scripts/verify-compose.py` passed chain, invalid
  admin token denial, injected failure, circuit open/shedding, recovery and Prometheus. Post-load verification also
  passed after driving the existing sliding window to its actual threshold. Application containers use loopback host
  ports 18080–18082 in project `resilience-framework-verification`. Grafana/Prometheus provisioning files are provided
  but those monitoring containers were not started during this check.
- JMH forked smoke run: [measured summary](benchmark-smoke.md), [raw JSON](../resilience-benchmarks/smoke-results.json).
  Five cases executed with deliberately short settings; no statistical superiority claim.
- k6 1,000-RPS / 10-second smoke run **failed** its zero-dropped-iterations threshold (exit 99): 9,493 completed, 507
  dropped, 18.43% HTTP failures; reported overall throughput 799.2 req/s including drain and p95
  180.3ms. [Raw output](results/k6-smoke.txt). This does not establish sustainable 1,000-RPS capacity. Generator was
  limited to one CPU/256MiB, max 300 VUs; no host/CPU profiling was collected, so bottleneck attribution is unverified.
- Maven POMs and supplied JSON parsed; documentation local links
  checked. [Resolved runtime dependency filenames](results/resolved-runtime-dependencies.txt) extracted from the final
  packaged order service.

Exact local build:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/resilience-m2 -f enterprise-resilience-framework/pom.xml verify
```

Normal fresh checkout: set JAVA_HOME to JDK 21 then `mvn -B verify` from the project directory. Internet access is
required on first resolution.

## Not established

No production readiness, 10k/100k RPS capacity, full JMH contention/allocation baseline, exhaustive fault matrix, DNS
partition, real connection reset, database connection-pool saturation, long soak, security penetration test, Kubernetes
apply/rollout, Helm install, CI execution, or Grafana/Prometheus container validation is claimed. The 35-scenario review
marks pending automated tests explicitly. See [limitations](limitations.md) and [performance](performance.md).

Configuration replacement intentionally resets the circuit and overlaps old/new resources until in-flight calls exit.
Cross-service deadline headers and automatic thread-local context propagation are not implemented. Core deadlines,
explicit caller deadline overload and the local concurrency invariants are tested; they do not imply remote cancellation
or global load control.
