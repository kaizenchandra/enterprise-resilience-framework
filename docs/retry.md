# Retry safety and budgets

UNSAFE is the annotation default. Callers must explicitly choose IDEMPOTENT or IDEMPOTENCY_KEY. A key requires verified
atomic deduplication, retention longer than all retries, and reconciliation of ambiguous outcomes at the server. A
payment timeout may mean the capture committed: do not retry or return payment success without that contract. The
framework propagates the timeout; it does not resolve ambiguity.

Default classifier retries IOException (including RetryAfterException) and TimeoutException only. Translate HTTP 429/503
in the adapter. Other HTTP statuses require a deliberate business rule. RetryAfterException carries a validated
duration; delay is max(local backoff, server delay). Parse integer seconds or HTTP-date in a real HTTP adapter using a
wall clock, clamp/validate, and never shorten Retry-After to force another attempt. The demo uses a fixed one-second
hint matching its simulator response.

Backoff supports fixed or capped exponential with full jitter uniformly in [0, cap]. Doubling caps before overflow.
Maximum attempts includes initial call. Maximum elapsed and overall deadlines bound backoff and additional attempts;
per-attempt timeout bounds each wait for completion. Interrupted sleep propagates; execute restores caller interrupt
status. No retry holds a bulkhead permit except a timed-out previous action still exiting.

Per-call bounds are supplemented by LocalRetryBudget or the Redis-backed fleet token budget;
see [fleet retry budget](fleet-retry-budget.md). Assign one retry owner per dependency chain: 3 attempts at each of 3
layers can create 27 leaf calls. Gateway demo uses one attempt. Nested programmatic retry ownership is a documented
caller responsibility, not automatically detected. Observe retries divided by logical call count; configure a shared
Redis token bucket to cap aggregate additional attempts.
