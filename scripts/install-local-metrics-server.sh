#!/bin/sh
# ARM64 OrbStack fallback for environments where registry.k8s.io image pulls stall.
set -eu
cd "$(dirname "$0")/.."
resilience_metrics_context=${KUBE_CONTEXT:-orbstack}
if [ "$resilience_metrics_context" != orbstack ]; then
  echo 'This local-image fallback is restricted to OrbStack. Use the official manifest/images for other clusters.' >&2
  exit 1
fi
resilience_metrics_dir=$(mktemp -d)
trap 'rm -rf "$resilience_metrics_dir"' EXIT INT TERM
curl --fail --location --max-time 90 \
  https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.1/metrics-server-linux-arm64 \
  -o "$resilience_metrics_dir/metrics-server-linux-arm64"
python3 - "$resilience_metrics_dir/metrics-server-linux-arm64" <<'PY'
import hashlib,pathlib,sys
binary=pathlib.Path(sys.argv[1])
assert hashlib.sha256(binary.read_bytes()).hexdigest()=='0a3a052c44111dd0cc4ee86729d1eb621dd12b064f4dfb92e012d420ef0fd06f', 'Official release digest mismatch'
PY
docker build -f docker/metrics-server.Dockerfile -t resilience/metrics-server:v0.8.1-verified "$resilience_metrics_dir"
kubectl --context "$resilience_metrics_context" apply -f k8s/metrics-server-v0.8.1.yaml
kubectl --context "$resilience_metrics_context" -n kube-system set image deployment/metrics-server metrics-server=resilience/metrics-server:v0.8.1-verified
kubectl --context "$resilience_metrics_context" -n kube-system patch deployment/metrics-server --type=strategic --patch-file=k8s/metrics-server-local-resources.json
kubectl --context "$resilience_metrics_context" -n kube-system rollout status deployment/metrics-server --timeout=120s
kubectl --context "$resilience_metrics_context" get --raw /apis/metrics.k8s.io/v1beta1/nodes
