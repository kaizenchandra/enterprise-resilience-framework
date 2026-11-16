# Remaining-work execution record — 2026-11-15

Historical extension snapshot. The subsequent [release-gate execution](release-gates.md) supersedes the pending
metrics-server/CNI/HPA/HA entries below and records 64 passing tests plus failed high-load/durability gates.

The requested extensions are implemented and tested. This record supersedes the initial 33-test snapshot in
verification.md; historical JMH/k6 measurements remain historical and are not claims about current executor overhead or
capacity.

## Implemented

- Fleet retry governance: bounded local token buckets and a separate Redis adapter, atomic server-time spending across
  JVMs, bounded identity/connection counts, coherent-settings checks and fail-closed extra retries. Initial attempts do
  not consult the store. Store lookups have a separate bounded eight-worker executor, with at most 150 ms wait within
  the shared deadline; noncooperative store code cannot consume initial-attempt workers.
- Automatic context propagation: immutable parent deadlines, async-submission budgets, per-attempt/fallback TimeLimiter
  context, scoped restore, SLF4J MDC and OpenTelemetry Context across framework workers; arbitrary inheritable
  thread-local copying is disabled; validated inbound servlet budgets and W3C headers; propagation-aware JDK HTTP
  adapter used by gateway and order demos.
- Reliability testing: real TCP reset, partial response, DNS failure, refusal, slow/429/503/500 responses, active
  shutdown, saturation, cancellation, configuration update with in-flight work, Hikari/H2 pool exhaustion, two Redis
  clients and two independent JVMs, plus configurable seeded soak.
- Kubernetes: isolated namespace validation of admission, readiness, PDB health, authenticated shared budget use,
  three-service propagation, local circuit opening/shedding, Redis removal, payment removal, recovery and rolling
  restart. Manifests now include authenticated Redis, explicit fleet-budget configuration, startup probes and namespace
  NetworkPolicy.
- Updated Compose: four application/infrastructure containers use the tested images and authenticated shared Redis;
  loopback application ports remain 18080–18082. Budget metrics dashboard panels and an unavailability alert were added.

## Actual results

**Java 21.0.13.0.3 / Maven 3.10.0: 62 tests passed, zero failures/errors/skips**, with external and soak profiles
enabled. [Per-suite JSON](results/remaining-work-tests.json), [text summary](results/junit-summary.txt), [tested Java/resource/POM fingerprints](results/tested-source.sha256).

The final 60-second soak observed:

| Measurement                              | Observed |
|------------------------------------------|---------:|
| Logical operations                       |  226,630 |
| Physical downstream attempts             |  151,364 |
| Expected failure/rejection outcomes      |   99,380 |
| Cancellation requests                    |    7,489 |
| Maximum physical concurrency             |        4 |
| Active work / held permits at completion |    0 / 0 |

These counts come from a mixed fault workload with deliberate errors; they are **not an SLO or offered-load benchmark**.
Earlier minute-long runs also passed, but no multi-hour/day soak was executed. The latest source passed TimeLimiter
context and arbitrary InheritableThreadLocal isolation regressions as well.

Live Kubernetes validation **passed** on the local OrbStack v1.36.5+orb1
cluster. [Sanitized report](results/kubernetes-validation.json) contains the exact namespace, checks, job logs, resource
status and events. The healthy-store check confirmed framework token state in authenticated Redis. Removing Redis
produced exactly one initial downstream attempt and no automatic extra attempts. Removing payment produced bounded 503
responses while order stayed ready. Restoring payment and rolling order restored successful HTTP calls and
trace/deadline continuity. Validation namespaces were deleted; other cluster workloads were not modified.

HPA resources were admitted, but their ScalingActive condition reports FailedGetResourceMetric because metrics-server is
absent. **Actual autoscaling behavior remains unverified.** No cluster-wide metrics component was installed.
NetworkPolicy resources were admitted; cross-namespace enforcement by the CNI was not exercised.

The [updated Compose check](results/compose-extension-verification.txt) **passed** unauthenticated Redis denial,
authenticated shared-budget use, admin-token denial, fault injection, OPEN shedding, eventual recovery through both
independent circuits, and Prometheus output. The harness now waits for readiness and requires eight consecutive healthy
responses with both relevant circuits CLOSED; it does not assume a fresh sliding window or that two local circuits
recover simultaneously. Compose remains running with the updated implementation. The reliability script created and
removed its own ephemeral Redis after verification.

## Reproduce

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; otherwise set your Java 21 path
sh scripts/verify-reliability.sh
# Or use an existing isolated test Redis:
REDIS_TEST_URI=redis://127.0.0.1:16379 mvn -Pexternal,soak -Dsoak.seconds=60 verify

# Build three images, each with its corresponding SERVICE argument:
docker build -f docker/Dockerfile --build-arg SERVICE=order-service \
  -t resilience/order-service:validation-v2 .
# Repeat for payment-service-simulator and demo-client, making images available to the cluster.
python3 scripts/validate-kubernetes.py --context orbstack
```

The execution sandbox required a writable Maven cache and explicit local-network access. Exact test invocation used
`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, `-Dmaven.repo.local=/tmp/resilience-m2`,
`-Pexternal,soak -Dsoak.seconds=60 verify`. Compose command/healthcheck variables are escaped for container-time
expansion, and startup checks reject an accidentally unauthenticated Redis configuration. No performance improvement is
claimed; the earlier failed 1,000-RPS check remains a capacity release gate.

## Limits of the evidence

No finite chaos suite is exhaustive. Multi-day soak, real Redis HA/failover and data-loss recovery, clock
discontinuities, multi-node partitions, production database/proxy cancellation, domain-specific authorization/fallback,
CNI isolation enforcement, HPA scale events, and sustained-load production rollout/rollback remain adoption gates.
Relative HTTP budgets approximate transit time; remote code may outlive cancellation. Default programmatic executor
constructors require an explicit budget for fleet governance. Security/transaction state and arbitrary clients/callbacks
need explicit integrations. See fleet-retry-budget.md, context-propagation.md and limitations.md.
