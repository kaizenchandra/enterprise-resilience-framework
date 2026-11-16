# Release-gate execution — 2026-11-15

**Release decision: HOLD.** CNI enforcement passed locally. The first HPA scale-up/down experiment passed, but the
post-stress repeat failed and metrics availability proved unstable. Redis client failover is functional, but strict
retry-budget durability failed. The tested configuration did not meet the 1k/10k/100k-RPS healthy load targets. These
are actual local results, not production qualification.

## Environment and method

Java 21.0.13.0.3; Maven 3.10.0; macOS ARM64 host with 11 logical CPUs and 18GiB RAM; local OrbStack Kubernetes
v1.36.5+orb1. All Redis/Sentinel nodes, application containers and generators shared the host with pre-existing
workloads, which were not stopped or changed. Local VM capacity is approximately 8.8GiB. Observed node CPU was 66–75%
during parts of testing; this was not a dedicated performance environment.

The finite load generator used its recorded cached k6 image ID, two CPUs, 512MiB and at most 256 VUs. Load went through
the actual Docker gateway → order → payment chain. Each application had a two-CPU/512MiB container limit. The healthy
acceptance criteria were zero dropped iterations, HTTP error rate below 1%, and p99 below 500ms (the last is a harness
criterion, not a claimed user SLO). Case durations were 30s at 100/1k RPS, 20s at 10k, and 10s at 100k.
Slow/failure/recovery cases are observational, with no availability pass claimed.

## Metrics-server installed

Installed upstream v0.8.1 resources. The official registry image blob download stalled, so a local image was built from
the official ARM64 release binary with published SHA-256
`0a3a052c44111dd0cc4ee86729d1eb621dd12b064f4dfb92e012d420ef0fd06f`. It reports v0.8.1, serves real pod/node metrics, and
the metrics APIService was initially Available and supplied the first HPA run. Post-stress collection later became
unavailable; the final installation record must be read together with the post-load observations. Kubelet certificate
verification remains enabled; no `--kubelet-insecure-tls` flag was added. The upstream aggregator APIService TLS
settings were unchanged. The fallback base image is cached python:3.13-alpine; this local build is not a substitute for
your production image provenance policy.

Post-load checks found readiness flaps and OOMKilled restarts under the initial upstream resource
configuration; [the captured termination state](results/metrics-server-post-load-oom.json) is retained. A bounded local
patch now sets a 256MiB Go memory target, 512MiB container limit, one-CPU limit, 200m/256Mi requests, startup probes,
and five-second probe timeouts. Readiness still depends on real collected metrics; no TLS checks or metric-storage
readiness checks were disabled. The HPA experiment is repeated after this change, and earlier results are retained. This
patch has not been qualified by repeating the full high-rate matrix.

Final idle health checks after the bounded deployment's restart found the metrics API Available and the demo returned
eight consecutive healthy responses. [Final metrics snapshot](results/metrics-server-final-health.json)
and [demo observations](results/release-final-demo-health.json) are recorded. This is a point-in-time recovery check,
not a stability pass under stress.

The metrics-server remains installed as
requested. [Installation evidence](results/metrics-server-installation.json), [upstream manifest](../k8s/metrics-server-v0.8.1.yaml), [reproduction script](../scripts/install-local-metrics-server.sh).
The reproduction helper intentionally targets local ARM64 OrbStack only.

## CNI enforcement: PASS

[Raw CNI report](results/cni-orbstack.json): all 15 checks passed, including six baseline positive connections; allowed
same-namespace ingress; denied unapproved same-namespace and cross-namespace ingress; denied egress; and restored
positive connectivity after removing the egress rule. Both direct Pod IPs and Service IPs were exercised with fresh
connections, three trials per deny/allow check. The initial probe ran before Service endpoint propagation and was
inconclusive; the corrected probe waits for all baseline paths to work before applying policies.

This empirically validates the local network stack's enforcement for these flows. It does not establish multi-node,
IPv6, host-network, external egress, DNS-specific, or L7 policy coverage. No replacement CNI was installed and no
existing namespace policy was changed. Both owned namespaces were deleted.

## HPA scaling: initial mechanics PASS; post-stress repeat FAIL

[Initial HPA report, timeline, events and load output](results/hpa-before-metrics-hardening.json): a real order-service
deployment, initially one replica, was targeted by 100-RPS in-cluster HTTP load for 120 seconds. CPU request was 100m,
CPU limit one core, target utilization 50%, and replica bounds 1–3. A low-CPU baseline was required before creating the
HPA; startup-only scaling was not used as proof.

The controller requested three replicas, two ready replicas were observed, and it scaled back to one ready replica after
load stopped. The test used a 30-second downscale stabilization window to keep the validation finite; this is not the
production default. It did not prove that all three replicas simultaneously became ready.

The load completed 11,826 HTTP requests and had **46.36% HTTP failures**, with dropped iterations also visible in raw
output. Therefore the experiment validates actual HPA activity, not an availability or latency improvement. Startup,
routing/keepalive distribution, CPU contention and readiness behavior need profiling under a dedicated workload before
production adoption. Metrics-server was left running; the test namespace and workload were removed.

**Post-stress repeat:** after the metrics memory/probe patch, a second [HPA run](results/hpa-validation.json) observed
scale-up to two ready replicas, but its load job failed before the full scale-down check. Events record
FailedGetResourceMetric, missing metrics, readiness/liveness timeouts and metrics API unavailability. The precise
load-container termination reason was not captured before cleanup, so it is not attributed to OOM or another cause. A
direct kubelet `/metrics/resource` request also timed out after 30.90 seconds with no response body. Thus HPA stability
is **not validated** on this host; the earlier one-time scale-up/down success must not be presented as a clean release
pass. The bounded metrics-server was restarted after workload cleanup. Other workloads and the node were not restarted.

## Redis HA: recovery PASS, durability FAIL

[Measured HA report](results/redis-ha.json), [Sentinel events](results/redis-ha-events.json), [Java probe output](results/redis-ha-probe.log).
The isolated topology had one primary, two replicas and three authenticated Sentinels, all on the same host. Both data
and Sentinel credentials were random runtime values, not committed. The framework now supports bounded `redis-sentinel`
discovery with at most five Sentinel endpoints and the same bounded data connection pool. Discovery is an administrative
startup dependency; request execution does not perform inline topology discovery.

- After spending seven of ten tokens and confirming the replication prefix on both replicas with a barrier, primary
  failure recovered in **2.210s** and exactly the remaining **three** tokens were granted.
- A replica process and replication link were isolated before spending another ten tokens. Crashing that primary and
  promoting the stale replica restored **ten** spent tokens; client recovery was **2.104s**. This is an observed
  violation of a strict durable fleet quota, consistent with asynchronous replication. No exactly-once or durable quota
  guarantee is claimed.
- Freezing two Sentinels and crashing the primary produced **zero grants** during the five-second quorum-loss
  observation. Restoring quorum eventually preserved the expected remaining **seven** tokens, but took **47.027s**.
  Server events show the frozen Sentinels entering TILT for 30 seconds before completing election/recovery.
- The original **40-second** quorum-recovery observation failed and is [retained](results/redis-ha-earlier-40s.log). A
  later **75-second administrative observation** measured the actual 47.027s recovery. Individual request budgets were
  not extended. Thus a 40s recovery objective remains failed; functional eventual recovery alone is not a production HA
  SLO pass.

The Java client's primary-loss and quorum-loss paths failed closed for extra retries. Initial attempts remain
independent of budget lookup decisions; that invariant also remains covered by earlier Kubernetes store-removal and Java
noncooperative-store tests. Redis HA did not turn this into a strongly consistent quota: choose a
durable/consensus-backed design if that guarantee is mandatory. Test cross-host failure domains, stale-primary fencing,
persistence loss and real network partitions before deployment. All owned HA containers/network were removed. Post-load
demo checks initially returned 503, then recovered naturally to eight consecutive healthy responses without resetting
circuits; [observations](results/post-load-demo-recovery.json) are retained.

Sources: [Redis Sentinel replication limits and TILT](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/), [Sentinel client discovery](https://redis.io/docs/latest/develop/reference/sentinel-clients/).

## Offered-load measurements

RPS below is k6's achieved **HTTP response/request rate including failures and drain time**. It is not successful
business throughput. Latencies are milliseconds, and dropped iterations are load-generator scheduling drops, distinct
from server-side rejections.

| Case            | Offered RPS | Achieved RPS | Dropped | HTTP failures | p50 ms | p95 ms | p99 ms | Result   |
|-----------------|------------:|-------------:|--------:|--------------:|-------:|-------:|-------:|----------|
| healthy-100     |         100 |         99.9 |       0 |         0.00% |    5.0 |   52.0 |  243.6 | PASS     |
| healthy-1000    |       1,000 |        860.7 |   4,174 |        10.80% |    5.1 |  297.9 |  785.9 | FAIL     |
| healthy-10000   |      10,000 |      2,511.6 | 145,121 |        23.68% |   11.4 |  247.8 | 1009.3 | FAIL     |
| healthy-100000  |     100,000 |        252.5 | 993,289 |        83.10% |  251.5 | 1096.1 | 2004.8 | FAIL     |
| slow-1000       |       1,000 |        642.9 |   5,963 |        98.49% |   18.3 |  755.3 | 1156.6 | OBSERVED |
| failure-1000    |       1,000 |        141.6 |  16,778 |       100.00% |  427.1 | 2441.4 | 4500.6 | OBSERVED |
| recovering-1000 |       1,000 |        465.7 |  15,353 |        82.58% |   70.9 | 1168.8 | 2003.9 | OBSERVED |

[Full load report](results/release-load.json) includes raw k6 summaries, before/after Prometheus snapshots, downstream
call counts, observed call amplification, and sampled container CPU/memory. At 100k offered RPS the generator could not
deliver the workload: the result is not a measurement of 100k-RPS application capacity. High-rate runs combine generator
and SUT saturation, so bottleneck attribution remains unverified without an independent adequately sized load-generator
fleet and profiling.

The recovering case cleared its 100% fault after about ten seconds and observed a first healthy response 1.74s later,
but its overall error/drop rate remained high. First recovery is not sustained recovery or an SLO pass. Fault cases
intentionally have no availability thresholds; successful command completion is labeled OBSERVED, not PASS.

An earlier partial run failed the 1k target with 74.34% errors; it
is [preserved](results/release-load-initial-partial.json), rather than discarded in favor of the later 10.80% result. A
stdout-pipe backpressure bug stalled that run's next case; the corrected harness streams output to files and bounds case
duration. The variation underscores why these local tests cannot be used to claim production capacity.

## Build and remaining release decision

The full Java 21 external/soak suite passed **64 tests with no failures/errors/skips** after Sentinel integration,
including invalid HA configuration checks. The seeded minute-long soak retained its four-call physical concurrency bound
and returned all
permits. [Test evidence](results/release-gates-tests.json), [source fingerprints](results/release-gates-source.sha256).

A [machine-readable gate summary](results/release-gates-summary.json) records the HOLD decision.

The production gates remain open for stable metrics/HPA collection on this host, healthy sustained 1k/10k/100k traffic
on a representative cluster, an independently provisioned generator, agreed recovery SLOs, cross-host Redis failure
domains, strict durability if required, long soaks and production rollout/rollback under sustained load. Prefer the
official metrics-server image in environments where registry access is available. Keep the experimentally demonstrated
data-loss and long-quorum-recovery behaviors in the operational runbook.
