# Fleet-wide retry budget

`RetryBudget` is a Spring-independent contract. `LocalRetryBudget` provides a synchronized bounded token bucket per
configured policy. The Spring starter enables local budgets by default (capacity 100, refill 10 tokens/second); plain
programmatic executor constructors retain the explicitly documented unlimited fleet-budget default, while
attempts/deadlines remain bounded. Supply a budget using the extended constructor for equivalent protection without
Spring.

`resilience-redis` is a separate adapter using Jedis 6.0.0. Every additional eligible retry acquires one token using an
original atomic Lua script against Redis. All replicas with the same namespace and policy identity share the bucket.
Initial attempts never consult Redis. The script uses Redis server time, caps refill, clamps backwards clock motion, and
refuses mismatched capacity/refill settings. Each bucket expires only after more than a full refill interval of
inactivity. Only startup-registered identities are accepted; the allowlist is bounded to 1,000 and the pool to eight
connections, with no pool wait. Registry replacement of an existing policy does not reset its fleet bucket.

```yaml
resilience:
  retry-budget:
    mode: redis
    redis-uri: ${RESILIENCE_RETRY_BUDGET_REDIS_URI}
    namespace: checkout-production
    capacity: 100
    refill-per-second: 10
```

For capacity B and refill R, a healthy consistent Redis primary permits at most approximately B + R×elapsed tokens over
an interval. This is an **absolute retry-rate budget**, not a ratio to initial traffic and not a limit on all dependency
calls. Initial traffic still needs bulkheads, quotas and ingress admission. Never use request/customer identity for
bucket keys. Share the identity intentionally across all callers that should compete for one downstream budget.
Different namespaces are independent budgets.

The budget is spent after backoff, before additional circuit admission; tokens are not refunded for late cancellation,
circuit rejection or ambiguous Redis timeouts. Conservative loss avoids double-spending. Redis denial/unavailability
preserves the preceding dependency exception and emits `resilience.budget_exhausted` or `resilience.budget_unavailable`;
successful reservation increments the retry metric. Budget rejection is not another downstream circuit failure.

Budget lookups execute in a separate eight-worker, zero-queue virtual-thread TimeLimiter with a 150 ms caller wait
capped by the shared deadline. Redis sockets have 50 ms connect/read timeouts and fail closed if insufficient time
remains for the configured IO reserve. OS DNS, TLS or native operations may outlive a cancelled lookup, but remain
bounded by those eight workers. They do not occupy initial-attempt workers. Redis pool or lookup-worker exhaustion
suppresses retries. No retry of Redis operations is performed by this adapter.

Use authenticated TLS, ACLs, a memory limit with `noeviction`, monitored capacity, and an operationally supported Redis
deployment. Demo Kubernetes Redis is authenticated but ephemeral and plaintext within the isolated namespace. Data loss,
eviction, flush, failover to stale state or restart can restore a full burst; this is not a durable exactly-once global
quota. Forward server-clock jumps can refill prematurely. Coordinate capacity changes across the fleet: mismatched
clients fail closed until compatible state expires. Do not silently change namespace to evade a depleted budget.
Production Redis HA/partition/failover characterization remains required.

Verification includes deterministic local refill/concurrency, two Redis clients, two **independent Java processes**
sharing seven tokens, configuration mismatch, unavailable store, and live Kubernetes store removal proving one initial
attempt with zero additional attempts.

Sources: [Redis atomic scripting](https://redis.io/docs/latest/interact/programmability/eval-intro/), [Redis rate-limiting algorithms](https://redis.io/docs/latest/develop/use-cases/rate-limiter/).
Atomicity is provided by a consistent primary; the application must still own durability and deployment semantics.

## Sentinel discovery and measured HA limits

The starter now accepts `mode: redis-sentinel`:

```yaml
resilience:
  retry-budget:
    mode: redis-sentinel
    master-name: resilience
    sentinels: [s1:26379, s2:26379, s3:26379]
    redis-password: ${REDIS_PASSWORD}
    sentinel-password: ${SENTINEL_PASSWORD}
    namespace: checkout-production
    capacity: 100
    refill-per-second: 10
```

Programmatic users can call `RedisRetryBudget.sentinel(...)`. The allowlist/token state is unchanged; an
eight-connection pool follows primary changes via up to five Sentinel endpoints. Sentinel topology discovery happens
during administrative construction and may fail startup if no primary can be discovered; business attempts do not
synchronously discover topology. The convenience factory uses authenticated plaintext inside the test network;
production TLS/ACL configuration requires an appropriate supported client integration before adoption.

The [live Sentinel lab](release-gates.md) demonstrated preserved replicated state, zero grants when quorum/primary was
unavailable, eventual reconnect, and **ten duplicated grants after acknowledged spending was not replicated**. Normal
failover recovered in 2.21 seconds; the quorum freeze/recovery path took 47.03 seconds, including Sentinel TILT.
Therefore the client is failover-aware, but neither strict durable quotas nor a 40-second quorum recovery objective
passed. All nodes shared one host; cross-host/zone HA remains unverified.
