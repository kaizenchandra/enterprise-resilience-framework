# Concurrency and visibility

Circuit monitor entry/exit supplies happens-before and a linearization point for all state changes. Snapshot reads are
coherent. A per-ticket CAS prevents duplicate completion; generation comparison under the monitor rejects stale
completions. LongAdder is useful for telemetry, but not for coherent window percentages or probe allocation.
AtomicReference/CAS state machines could reduce locks but introduce complex multi-field invariants; no contention
evidence currently justifies them. ReentrantLock supports interruptible lock acquisition but is unnecessary for these
short nonblocking sections.

Semaphore provides permit visibility and interruptible acquisition; AtomicBoolean prevents double release. Registry
synchronized methods publish fully initialized immutable config with new runtime resources. Updates reset circuit and
concurrency resources; old calls keep old permits. Therefore rapid updates can temporarily sum old/new capacities;
serialize and drain updates operationally. No network-triggered refresh is exposed. Rollback installs a previous
validated config as a new version. Existing telemetry gauges resolve the current policy; old in-flight work is not
included in those active gauges. Avoid frequent updates and unbounded retention by external callers.

Exceptions from telemetry are isolated; event sinks must be bounded, fast and nonblocking. No user callback runs while
the circuit lock is held. Classifiers should be pure and total. Fatal Errors are propagated and are not retry/fallback
candidates; process recovery, rather than normal circuit accounting, is expected after fatal VM errors.
