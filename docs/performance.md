# Benchmarks and comparison

```sh
mvn -B package
java -jar resilience-benchmarks/target/benchmarks.jar -prof gc -t 1 -rf json -rff results-1.json
java -jar resilience-benchmarks/target/benchmarks.jar -prof gc -t 16 -rf json -rff results-16.json
k6 run -e RPS=1000 scripts/load.js
k6 run -e RPS=10000 -e MAX_VUS=5000 scripts/load.js
# 100000 is a scale target requiring a distributed load generator and sized cluster.
```

JMH defaults: 3x1s warmup, 5x1s measurement, 2 forks. Shared benchmark state exposes monitor contention; returned values
are consumed by JMH. GC profiler reports allocations. Compare circuit admission+successful record against Resilience4j
2.3.0, not just raw admission. Standalone retry/bulkhead/deadline cases isolate costs. They do not establish end-to-end
benefit. A short local smoke run, if recorded, is not a reliable baseline.

The executed 1,000-RPS smoke failed its dropped-iterations threshold; see [verification](verification.md)
and [raw output](results/k6-smoke.txt). It is not a capacity claim.

For load tests record offered/achieved RPS, dropped iterations, p50/p95/p99, errors/rejections, dependency `/calls`,
retry amplification, recovery time and bulkhead utilization. Collect JFR/GC/CPU/heap on all services and host
specifications. Repeat normal/50%-failure/slow/recovering/saturated workloads. Baseline without resilience can target
simulator `/quote`; executor does not yet offer switches for a complete no-policy/each-policy load matrix. Do not
compare different topology latency as framework overhead.

| Area          | Custom implementation                                      | Resilience4j reference                                         |
|---------------|------------------------------------------------------------|----------------------------------------------------------------|
| State         | Three normal plus forced-open/disabled, generation tickets | Established state machine and administrative modes             |
| Windows       | Bounded count ring                                         | Count and time windows                                         |
| Retry         | Explicit safety, full jitter, bounded call budget          | Rich predicates, interval functions and integrations           |
| Isolation     | Semaphore + shared fixed virtual workers                   | Semaphore and thread-pool bulkheads                            |
| Timeout       | Interrupts Future; retains permit through exit             | TimeLimiter with cancellation semantics dependent on execution |
| Spring        | Small starter, one synchronous annotation                  | Broad Boot/AOP/reactive integration                            |
| Observability | Micrometer and existing span attributes                    | Mature event/metrics ecosystem                                 |
| Performance   | Must measure locally; no superiority claimed               | Benchmark pinned version under identical conditions            |
| Maintenance   | Team owns race, API, security and operations burden        | Larger community and operational history                       |

Official compatibility
sources: [Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Boot auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html), [Resilience4j Java requirements](https://resilience4j.readme.io/docs/getting-started), [circuit semantics](https://resilience4j.readme.io/docs/circuitbreaker).
Boot 3.5.16 supports Java 21. Resilience4j 2.x requires Java 17 or later. Exact transitive Micrometer/OpenTelemetry
versions are managed by Boot's BOM; inspect `mvn dependency:tree` for the resolved release artifact. The implementation
is original; no Resilience4j source was copied.

New release-gate load data is in [release-gates.md](release-gates.md) and results/release-load.json. The healthy 100-RPS
control passed, but healthy 1k/10k/100k offered-load targets failed on the contended local host. The generator was
bounded to 2 CPUs, 512MiB and 256 VUs; the 100k offered test was not delivered at 100k. Rejections/errors are included
in the reported achieved rate. Fault scenarios are observations with intentional failures, not SLO passes.
