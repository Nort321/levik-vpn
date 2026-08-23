#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly WORKFLOW_DIRECTORY="${REPOSITORY_ROOT}/.github/workflows"

if [[ ! -d "${WORKFLOW_DIRECTORY}" ]]; then
  printf 'Action pin policy passed: no workflow files are present.\n'
  exit 0
fi

workflow_list="$(mktemp "${TMPDIR:-/tmp}/levikvpn-workflows.XXXXXX")"
cleanup() {
  rm -f -- "${workflow_list}"
}
trap cleanup EXIT

find "${WORKFLOW_DIRECTORY}" -type f \( -name '*.yml' -o -name '*.yaml' \) -print0 >"${workflow_list}"

violations=0
workflow_count=0

while IFS= read -r -d '' workflow; do
  workflow_count=$((workflow_count + 1))
  line_number=0

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line_number=$((line_number + 1))
    trimmed="${line#"${line%%[![:space:]]*}"}"
    if [[ "${trimmed}" == \#* ]]; then
      continue
    fi

    if [[ "${line}" =~ ^[[:space:]]*(-[[:space:]]*)?uses[[:space:]]*:[[:space:]]*([^#[:space:]]+) ]]; then
      reference="${BASH_REMATCH[2]}"
      reference="${reference#\"}"
      reference="${reference%\"}"
      reference="${reference#\'}"
      reference="${reference%\'}"

      if [[ "${reference}" == ./* ]]; then
        continue
      fi

      if [[ "${reference}" =~ ^docker://[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
        continue
      fi

      if [[ "${reference}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?@[0-9a-f]{40}$ ]]; then
        continue
      fi

      relative_workflow="${workflow#"${REPOSITORY_ROOT}/"}"
      printf 'ERROR: %s:%d uses mutable or invalid action reference %q\n' \
        "${relative_workflow}" "${line_number}" "${reference}" >&2
      violations=$((violations + 1))
    fi
  done <"${workflow}"
done <"${workflow_list}"

if ((violations > 0)); then
  printf 'Action pin policy failed with %d violation(s).\n' "${violations}" >&2
  exit 1
fi

printf 'Action pin policy passed for %d workflow file(s).\n' "${workflow_count}"
