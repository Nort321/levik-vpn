#!/usr/bin/env bash
set -euo pipefail

workspace_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
go_bin="${GO_BIN:-go}"
python_bin="${PYTHON_BIN:-python3}"
if [[ "$(${go_bin} version)" != go\ version\ go1.26.5* ]]; then
  printf 'Go 1.26.5 is required (set GO_BIN)\n' >&2
  exit 1
fi

(cd "${workspace_dir}/node-agent" && env GOTOOLCHAIN=local GOFLAGS=-mod=readonly "${go_bin}" test ./...)
(cd "${workspace_dir}/fork/wdtt-plus-v15" && env GOTOOLCHAIN=local GOFLAGS=-mod=readonly "${go_bin}" test ./...)
(cd "${workspace_dir}/fork/wdtt-plus-v15/go_client" && env GOTOOLCHAIN=local GOFLAGS=-mod=readonly "${go_bin}" test ./...)
(cd "${workspace_dir}/fork/wdtt-plus-v15/go_client/third_party/anet" && env GOTOOLCHAIN=local GOFLAGS=-mod=readonly "${go_bin}" test ./...)
(cd "${workspace_dir}" && env PYTHONDONTWRITEBYTECODE=1 "${python_bin}" -m unittest -v scripts/test_node_lifecycle_smoke.py)
