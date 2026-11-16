# Bulkheads and bounded workers

Semaphore permits bound actual downstream execution, regardless of virtual or platform caller threads. Acquisition uses
a deadline-capped, interruptible timed tryAcquire, including zero wait for immediate shedding. Permit close is
idempotent. Try-with-resources in the worker retains the permit if user code ignores cancellation; releasing at caller
timeout would admit more physical work than configured.

TimeLimiter uses a shared fixed-size ThreadPoolExecutor with virtual thread workers and SynchronousQueue, aborting
saturated submissions. Async coordinators use a second fixed-size virtual thread pool with no queue. This provides
global admission isolation in addition to per-policy semaphores. Pool threads can time out when idle. Platform thread
pools cost more native memory; a virtual-thread-per-task executor alone would not bound pending work. No separate
endpoint thread-pool module is provided: shared bounded attempt workers supply coarse isolation; a dedicated executor
per endpoint would multiply resources.

Virtual threads do not increase connection-pool size, make CPU work faster, or remove downstream quotas. Java 21
monitors/native operations may pin carriers. Short circuit monitor operations contain no user code or blocking I/O.
Connection pools and HTTP concurrency must also be bounded. Fair semaphore waits reduce starvation but do not ensure
endpoint fairness when policies share a global worker pool. Partition capacity by workload/SLO and tune global capacity
above the sum of desired active work where affordable.
