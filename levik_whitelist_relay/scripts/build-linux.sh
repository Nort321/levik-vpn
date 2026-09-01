#!/usr/bin/env bash
set -euo pipefail

workspace_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${workspace_dir}/build/linux-amd64"
go_bin="${GO_BIN:-go}"
if [[ "$(${go_bin} version)" != go\ version\ go1.26.5* ]]; then
  printf 'Go 1.26.5 is required (set GO_BIN)\n' >&2
  exit 1
fi
mkdir -p "${output_dir}"

(
  cd "${workspace_dir}/fork/wdtt-plus-v15"
  env GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=0 GOOS=linux GOARCH=amd64 "${go_bin}" build -buildvcs=false -trimpath -ldflags='-s -w -buildid=' -o "${output_dir}/levik-wdtt-server" .
)
(
  cd "${workspace_dir}/node-agent"
  env GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=0 GOOS=linux GOARCH=amd64 "${go_bin}" build -buildvcs=false -trimpath -ldflags='-s -w -buildid=' -o "${output_dir}/levik-relay-agent" ./cmd/levik-relay-agent
)
printf 'built matched server and node-agent in %s\n' "${output_dir}"
