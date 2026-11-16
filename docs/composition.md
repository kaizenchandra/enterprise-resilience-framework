# Composition

Default: overall deadline → fallback boundary → retry loop → circuit admission → bulkhead acquisition → attempt
timeout/action. Circuit admission occurs before submitting the attempt; worker acquires semaphore. Time spent waiting
for bulkhead is within attempt and overall budgets. Each actual eligible attempt, including attempt timeout, records a
circuit outcome; local rejections and explicit cancellation are ignored. Retry sees circuit rejection and stops.
Fallback never records success in the dependency circuit.

Fallback → CircuitBreaker → Retry counts one final logical outcome, hiding successful-but-expensive retries from failure
percentage. Fallback → Retry → CircuitBreaker counts individual attempts, detects failing dependencies sooner, and may
stop a logical call mid-retry. Neither is universally correct. This implementation exposes low-level primitives for
custom composition but intentionally provides one executor accounting model; no configurable decorator-order language.

Fallback requires explicit exception eligibility and a business-safe implementation. It must enforce authorization and
tenant scope on cached data. Payment success, authentication, ownership and confirmed order state are unsuitable
fabricated responses. Fallback exceptions propagate. Eligible fallback is attempted only while budget remains.
