# ADR 001: bounded workers and attempt accounting

Accepted for this implementation. Keep contracts and algorithms free of Spring; integrate via separate modules. Choose
short monitor-protected count ring over a lock-free state machine. Choose per-attempt circuit accounting and explicit
call retry safety. Hold permits to physical action completion. Fixed virtual workers with zero queue bound
noncooperative work. Atomic registry replacement resets state and may overlap resources; updates require operational
draining. No distributed breaker coordinator in the call path.

Consequences: predictable bounded storage and simple state reasoning; lock contention requires measurement; cancellation
remains cooperative; async is a blocking adapter; context propagation is explicit. Prefer mature Resilience4j
operational integrations for general production use.
