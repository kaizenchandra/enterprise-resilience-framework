# Circuit breaker

A monitor protects admission, generation, counters and a byte ring. Each eligible completed call contributes one
denominator entry, with failure and slow bits. The ring subtracts its evicted entry in O(1); ignored outcomes contribute
neither numerator nor denominator. Snapshot is consistent. Count-window ordering follows completion, not admission.
Results admitted before reset/state transition are discarded.

Failure or slow percentage >= threshold opens after minimumCalls. OPEN rejects without counting a failure. Admission
lazily advances OPEN to HALF_OPEN after monotonic openDuration. HALF_OPEN allocates at most probes tickets; any eligible
failed/slow probe reopens immediately; all probes must succeed to close. Ignored/cancelled probes release a slot without
recovery evidence. Attempt timeouts are eligible failures, cancellation is ignored. Ticket's AtomicBoolean makes
completion idempotent. Old-generation completions cannot mutate a new window. Manual reset, disable and forceOpen are
explicit local administration APIs.

State transitions reset the ring. Lifetime ignored/rejected/transition counters remain; window gauges drop to zero on
transition. Open duration is measured from transition, not last failure. HALF_OPEN with only ignored calls can persist;
alert and inspect classification before administrative reset. Long-running direct users of tickets must complete them;
executor guarantees a completion decision for Exception paths.

Count window offers bounded O(N) memory and O(1) updates. Time windows instead expire buckets according to elapsed time,
with O(bucket count) memory and coarser boundaries. A fixed window has boundary bias; ring buffers are storage for
either count outcomes or time buckets. Bucket aggregation reduces memory and speeds snapshots but loses per-call
precision. No time-window implementation is advertised here.
