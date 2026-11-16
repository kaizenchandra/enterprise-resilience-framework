# Automatic context and deadline propagation

Core `ResilienceContext` stores a scoped Deadline. Child deadlines retain immutable parent links, so construction,
scheduling and nested calls cannot renew an existing budget. `ResilienceExecutor` automatically inherits the current
context deadline, including async submission time. Attempt workers expose the minimum of overall, retry elapsed and
per-attempt budgets; fallback and standalone TimeLimiter workers also receive their actual limited deadline. Scope close
restores the prior value rather than merely clearing an outer caller's context.

`TaskContext` captures a submission snapshot and opens/closes it on the worker. Core defaults propagate framework
deadlines. The Spring starter's `ObservabilityTaskContext` additionally captures OpenTelemetry Context and SLF4J MDC. It
covers async coordinator, attempt, fallback and budget-worker boundaries, including the supplier of `executeStage`.
Worker values are restored after success, exception or interruption, preventing cross-request leakage. Worker factories
disable automatic InheritableThreadLocal inheritance. Arbitrary ThreadLocals, transactions, servlet request objects and
Spring SecurityContext are deliberately not cloned. Applications can replace the TaskContext bean to support explicitly
approved additional context.

In servlet applications the starter automatically registers `DeadlinePropagationFilter`. `X-Resilience-Timeout-Millis`
is a positive remaining duration. Missing values receive the local maximum (default two seconds); oversized values are
clamped; malformed/negative/overlong values return 400 and zero returns 408. Set `resilience.inbound-max-budget` to
change the ceiling. W3C traceparent/tracestate are extracted with OpenTelemetry's W3C propagator. Baggage and arbitrary
user headers are not automatically copied.

Use the auto-configured `ResilienceHttpClient` JDK adapter (as the examples do). It replaces supplied propagation
headers with current trusted context, injects W3C trace context, caps the transport timeout to remaining time and sends
remaining milliseconds minus a two-millisecond reserve. Spring annotations and programmatic calls use the same scoped
execution machinery. The `/context` demo route traverses gateway → order → payment and returns only the leaf's remaining
milliseconds and trace ID for verification. No financial data is involved.

This is automatic for the supplied servlet filter, executor and HTTP adapter, **not arbitrary clients or arbitrary
CompletableFuture callbacks**. RestClient/WebClient/custom executors require corresponding adapters or explicit snapshot
wrapping. Servlet async dispatch outside the framework's async APIs requires its own context integration. No tracing
SDK/exporter or synthetic child spans are created; supply normal OpenTelemetry instrumentation when you need full span
generation/export.

Relative deadlines avoid sending nanoTime values between hosts. They cannot perfectly subtract unknown network transit
time; the two-millisecond reserve is conservative only for some local networks, not a bound on WAN delay. Each original
caller's monotonic deadline and transport timeout remain authoritative for its wait. A remote operation may still run
after cancellation. Test reserve values against your network and use a clock-skew-aware absolute deadline protocol if
stricter remote accounting is necessary.

Tests cover immutable parent caps, implicit async deadlines, MDC/trace restore after exceptions, nonleakage,
malformed/expired/clamped headers, protected outbound header replacement and real HTTP round trips. Kubernetes validates
the three-service chain with the same trace ID and a decreasing leaf budget.

Sources: [OpenTelemetry propagation API](https://opentelemetry.io/docs/specs/otel/context/api-propagators/), [W3C Java propagator](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/trace/propagation/W3CTraceContextPropagator.java).
