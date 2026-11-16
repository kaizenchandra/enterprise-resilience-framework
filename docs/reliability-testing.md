# Chaos, soak and Kubernetes validation

Run all repeatable reliability checks with Java 21 and Docker:

```sh
sh scripts/verify-reliability.sh
# Longer local soak; size resource limits deliberately before increasing load.
SOAK_SECONDS=3600 sh scripts/verify-reliability.sh
```

The script creates a bounded, loopback-only ephemeral Redis and removes it on exit. Alternatively set REDIS_TEST_URI and
run `mvn -Pexternal,soak -Dsoak.seconds=60 verify`. Default Maven runs exclude the external Redis and timed soak groups;
enabling them requires a real Redis URI. CI enables both groups. The workflow template must live in repository-root
`.github/workflows` to execute; this project currently resides in a larger workspace directory.

Fault tests use real TCP SO_LINGER resets, a short HTTP response with an intentionally oversized Content-Length, an
actual `.invalid` DNS lookup, connection refusal, delayed responses, HTTP 429/503/500, interrupted/cancelled stages,
no-queue worker saturation, shutdown of active work, and configuration replacement with an old call still running.
HikariCP with a real H2 connection held outside the framework demonstrates connection-pool saturation on a virtual
worker, cancellation, permit recovery and a subsequent successful SQL query. This is connection-acquisition testing, not
a production database failure test.

The seeded mixed-failure soak runs eight platform-thread callers against bounded virtual workers and a four-permit
bulkhead. It combines IO failures, business failures, jittered safe retries, cancellation, slowness, circuit recovery
and an aggregate local retry budget. It asserts physical concurrency never exceeds four, all workers return their
permits, expected failure/cancellation paths occur and useful calls continue. Counts are measurements, not
throughput/SLO benchmarks. Soak duration is configurable from five seconds to 24 hours. A passing minute does not
establish multi-day stability or absence of races.

## Live Kubernetes

Build each service using `docker/Dockerfile`, tagging `resilience/<service>:validation-v2`. Ensure these images and
`python:3.13-alpine` are available to the selected local cluster, then:

```sh
python3 scripts/validate-kubernetes.py --context orbstack
# Add --keep only when you want to retain the isolated namespace for inspection.
```

The script creates a unique owned namespace and Secrets in memory, performs server-side admission validation, applies
manifests, waits for all deployments, checks PDB health and executes real in-cluster HTTP jobs. It tests propagation,
malformed headers, authenticated fault denial, authenticated shared token state, every order replica's OPEN state,
unchanged downstream count during shedding, recovery, complete Redis removal with exactly one initial dependency
attempt, complete payment removal while order stays ready, and graceful rolling restart followed by successful HTTP
traffic. It stores a sanitized report and requests namespace deletion by default; no existing workloads are scaled or
stopped.

Manifests include startup/readiness/liveness probes, Secrets, bounded resources, HPA, PDB, graceful shutdown, rolling
deployment and namespace-scoped NetworkPolicy. The NetworkPolicy's enforcement depends on the CNI; resource admission
alone is not proof of isolation. HPA resources are admitted but runtime scaling requires a working metrics-server.
Report ScalingActive conditions separately from rollout checks; do not present absent metrics as successful autoscaling.
No cluster-wide component is installed by this script.

No finite test suite is exhaustive. Remaining release drills include multi-hour/day runs under realistic traffic, forced
Redis failover/durability and clock jumps, multi-node network partitions, transport-specific cancellation, production
database/proxy failure, tenant-aware fallback authorization, CNI enforcement, HPA scale events and production
rollout/rollback under sustained load. See the result files for what actually ran.

Sources: [server-side dry-run](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_create/kubectl_create_poddisruptionbudget/), [rollout status](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_rollout/kubectl_rollout_status/).
