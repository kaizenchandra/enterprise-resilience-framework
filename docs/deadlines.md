# Deadlines, timeout and cancellation

Deadline measures elapsed monotonic nanoseconds from a start point, avoiding wall-clock adjustment. Overall budget
starts on execute entry after registry resolution. Remaining budget covers circuit admission, bulkhead wait, attempt
waiting, backoff and fallback. Each attempt wait is min(perAttempt, remaining). No retry receives a fresh overall
timeout. Tests inject Ticker and Sleeper; actual Future waiting uses the JDK monotonic timed wait.

A caller timeout cancels the Future with interruption. This is not a guarantee of stopping arbitrary code. The
underlying task retains its permit and bounded worker until it exits. Late results do not update the circuit again.
Interruptible HTTP clients can abort send; a remote server may already have committed. Noninterruptible operations
consume capacity until real completion and must have transport-level deadlines. Fallback likewise executes on a bounded
worker and can outlive its caller if it ignores cancellation. Explicit caller interruption and CompletableFuture
cancellation do not trigger fallback.

executeStage is a bounded blocking bridge, not a reactive operator. It cancels the adapted CompletableFuture on worker
interruption; some CompletionStage implementations do not propagate cancellation upstream. Supply a client with known
cancellation semantics. Do not call blocking APIs on event loops.

Cross-service deadline headers are implemented by the starter's servlet filter and ResilienceHttpClient. The executor
automatically inherits the current deadline, including async submission time; explicit caller Deadline overloads remain
supported. Each hop clamps relative milliseconds and subtracts an outbound reserve. MDC and OpenTelemetry context are
restored across framework workers. See [context propagation](context-propagation.md) for exact supported boundaries,
validation and network-transit limitations.
