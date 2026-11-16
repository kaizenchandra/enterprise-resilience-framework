# Measured JMH smoke run

Java 21.0.13.0.3, macOS aarch64; one thread, one fork, one 200ms warmup and two 200ms measurements. This is harness
validation, not a statistically reliable performance baseline. No GC profiler was run. A separate end-to-end k6 smoke is
recorded in verification.md.

| Benchmark           | Observed ops/s |
|---------------------|---------------:|
| bulkhead            |     19,289,658 |
| circuitSuccess      |     25,857,335 |
| deadline            |     49,759,139 |
| resilience4jSuccess |     96,617,595 |
| retryEvaluation     |    192,033,138 |

Raw data: [smoke-results.json](../resilience-benchmarks/smoke-results.json). Ticket allocation and configuration
differences affect comparisons. Do not infer production superiority from this short run.
