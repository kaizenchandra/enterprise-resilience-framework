# Local-cluster fallback using the official, checksum-verified Linux ARM64 release binary.
FROM python:3.13-alpine
COPY --chmod=755 metrics-server-linux-arm64 /usr/local/bin/metrics-server
USER 1000
ENTRYPOINT ["/usr/local/bin/metrics-server"]
