# Security

No arbitrary expressions or class loading from configuration. Validate names, duration, thresholds,
attempt/concurrency/window bounds and registry capacity. Never derive policy identity from request/user IDs. Default
errors do not expose payloads or secrets. Metrics labels contain configured dependency identities only.

Registry updates are an in-process trusted administrative API, not a public mutation endpoint. Add authenticated
RBAC/audit and rate limits before exposing any management function. Read-only Actuator resilience endpoint is demo-only;
Kubernetes binds management to a separate port not published in Services. Restrict management scraping by network policy
in production. Compose binds host ports to loopback. Simulator admin token is mandatory and compared with
MessageDigest.isEqual; TLS termination and token rotation remain deployment responsibilities. No secret values are
committed. Shared Redis credentials come from Kubernetes Secret references or required Compose environment variables.
Redis uses noeviction and memory/connection limits; production also needs TLS/ACLs and durable HA. Inbound deadline
headers are validated/clamped; unsupported trace baggage is not propagated. Grafana password is required. Simulator
partial output is a malformed-body test, not a real TCP reset injector; use a network proxy for transport faults.

Fallback must apply authorization on read and cache reuse. Do not log sensitive customer state or use it in metric tags.
Container examples run nonroot with Kubernetes privilege restrictions. Validate/pin image digests and scan dependencies
in your release pipeline; version tags alone are not supply-chain verification.
