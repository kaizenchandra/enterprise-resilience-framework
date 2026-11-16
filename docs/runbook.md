# Operations and deployment

Build: `mvn -B verify`. Local demo: README commands then `sh scripts/demo.sh`. Faults: set 100% 503 to open circuit; set
delayMillis=1000 for transport/attempt timeouts; set zero failures/delay then wait open duration and send probes.
Compare `/calls` with gateway requests to detect amplification. NEVER use simulator payment quotes as financial
responses.

Inspect state (CLOSED=0, OPEN=1, HALF_OPEN=2, FORCED_OPEN=3, DISABLED=4), available permits, retries, timeouts and
latency. OPEN should reduce dependency traffic. Saturation with low CPU can mean hung downstream calls or depleted
connection pools. Capture thread dumps/JFR, inspect actual worker exit, and fix transport timeout before raising
capacity. A metrics outage must not fail business calls; sink callbacks still must not block. Tune alert intervals
against recovery duration to avoid flapping/alert storms.

Kubernetes: build three images with Dockerfile SERVICE argument and matching `resilience/<service>:1.0.0` tags,
load/push them into your cluster. Create Secret `resilience-demo` with keys `admin-token`, `redis-password`, and
`redis-uri` from your secret manager, then `kubectl apply -f k8s/demo.json`. All application services are ClusterIP;
management port 9091 is not exposed. HPA requires metrics-server. PDB needs two healthy replicas. Review namespace
network policy, TLS, image registry, security and resource budgets before rollout. No cluster deployment has been
assumed.

Pod restarts reset local circuits, increasing cold traffic. Roll out gradually, cap readiness traffic externally, use
jitter and retain semaphore limits. Do not fail readiness solely because payment is unavailable: that can restart
healthy clients and worsen the outage. Dynamic changes replace runtime state; drain and audit updates rather than
repeatedly resetting an open circuit. Roll back by publishing prior immutable config, with a new version.

Retry budgets: watch `resilience_budget_exhausted_total` and `resilience_budget_unavailable_total`. Exhaustion is
intentional load shedding; unavailability indicates Redis/network/worker capacity or config mismatch. Initial calls
remain independent of Redis budget decisions. Do not bypass an exhausted namespace or raise budgets during an incident
without measuring downstream capacity. See fleet-retry-budget.md.

Deadline/context support is automatic only for the documented adapters. Validate a known trace ID and shrinking
remaining time through `/context` in the demo; do not expose diagnostic endpoints as a production business API. Use
scripts/validate-kubernetes.py for owned-namespace validation; it generates the necessary Secrets without committing
them and cleans up by default.

Current release gates: see release-gates.md. Sentinel quorum freezes can trigger a 30-second TILT interval plus
election/client recovery; a measured recovery took 47 seconds. During this interval retry-budget lookups fail closed.
Asynchronous failover can restore already-spent tokens; never use this budget as a durable financial/security quota. CNI
tests are empirical and local, while HPA scaling is not an availability guarantee. metrics-server v0.8.1 remains
installed; its local fallback image was built from a verified official binary without disabling kubelet TLS checks.
