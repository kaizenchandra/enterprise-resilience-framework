# Release gates and limitations

The framework now includes fleet-wide Redis retry budgets, automatic deadline/MDC/OpenTelemetry propagation for its
executor/servlet/HTTP adapters, expanded transport and connection-pool chaos tests, a configurable soak, and live
isolated Kubernetes validation. See [latest evidence](remaining-work-verification.md). None of these establishes
production readiness.

Remaining capabilities: server-side financial idempotency/reconciliation; adaptive concurrency and general rate
limiting; fully nonblocking reactive execution; time-based windows; automatic distributed configuration refresh;
circuit-state preservation during updates; dedicated endpoint thread-pool module; HTTP-date Retry-After parser;
authorization-aware domain caches. Core programmatic constructors use an unlimited aggregate retry-budget default unless
you inject a budget; the starter defaults to a bounded local budget and supports shared Redis mode.

Annotation support remains public synchronous proxy calls only. SecurityContext, transactions, arbitrary ThreadLocals,
external executors and arbitrary CompletionStage callbacks are not automatically copied. The starter propagates only
framework deadlines, MDC and OpenTelemetry Context at supported boundaries. HTTP propagation requires the supplied
adapter or equivalent client integration. Relative network budgets do not perfectly account for unknown transit delay;
cancellation remains cooperative. The integration annotates/propagates existing spans and does not configure an
SDK/exporter or create a complete tracing topology.

Redis protection assumes a consistent primary and coherent settings. Restart, eviction, failover data loss or clock
jumps can restore bursts. Demo Redis is ephemeral, authenticated and plaintext within its network; production requires
operational HA/durability, ACL/TLS, noeviction and failover tests. Budget checks use a separate bounded pool so stuck
lookups do not take initial-attempt worker slots, but aggregate initial traffic still needs independent admission
limits.

Policy replacement overlaps old/new capacities until old actions exit. Current-policy gauges omit old work and
transition gauges reset on replacement. Global attempt capacity can still create cross-policy interference. External
sync callers must bound admission.

A minute-long seeded soak is not a multi-day production soak, and no finite test matrix is exhaustive. Production
database/multi-node partition tests, cross-host Redis HA/durability, broader CNI policy coverage, HPA availability
during sustained load, security review, realistic sustained-load benchmarks and rollout/rollback under load remain
release gates. Prefer Resilience4j unless a supported benefit justifies custom ownership.

Local CNI ingress/egress checks passed. Initial HPA mechanics passed with metrics-server installed, but the post-stress
repeat failed and metrics collection lost readiness. High-rate capacity gates and strict Redis durability do not pass.
See [release-gate results](release-gates.md); installing metrics-server does not qualify production scaling or
availability.
