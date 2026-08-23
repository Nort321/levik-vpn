#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/android-release.yml"

if [[ ! -f "${WORKFLOW}" || -L "${WORKFLOW}" ]]; then
  printf 'ERROR: Android release workflow is missing or is not a regular file.\n' >&2
  exit 1
fi

required_literals=(
  'workflow_dispatch:'
  'environment: production-release'
  'cancel-in-progress: false'
  'signing_identity_decision:'
  'approved-current-direct-play-identities'
  'refs/tags/${release_tag}'
  'refs/remotes/origin/main'
  'secrets.DIRECT_SIGNING_KEYSTORE_BASE64'
  'secrets.PLAY_SIGNING_KEYSTORE_BASE64'
  'secrets.DIRECT_OTA_PRIVATE_KEY_PEM'
  'secrets.DIRECT_UPDATE_MANIFEST_PUBLIC_KEY'
  'secrets.DIRECT_UPDATE_SIGNING_CERTIFICATE_SHA256'
  'secrets.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER'
  'verifyAllDependencyLocks'
  ':app:cyclonedxDirectBom'
  'lintDirectRelease lintPlayRelease'
  'assembleDirectRelease'
  'bundlePlayRelease'
  'generate-direct-update-manifest.sh'
  'validate-play-bundle-metadata.sh'
  'generate-native-sbom.py'
  'build-corresponding-source.sh'
  'gh release create'
  '--draft'
  'repository is private'
)

violations=0
for literal in "${required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${WORKFLOW}" >/dev/null; then
    printf 'ERROR: Android release workflow is missing required gate: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

on_block="$(awk '
  /^"on":$/ { in_on = 1; next }
  in_on && /^[^[:space:]]/ { exit }
  in_on { print }
' "${WORKFLOW}")"
if grep -E '^[[:space:]]{2}(push|pull_request|release|schedule):' <<<"${on_block}" >/dev/null; then
  printf 'ERROR: Android production release workflow must remain manual-only.\n' >&2
  violations=$((violations + 1))
fi

release_create_count="$(grep -F -c -- 'gh release create' "${WORKFLOW}" || true)"
if [[ "${release_create_count}" != "1" ]]; then
  printf 'ERROR: Android release workflow must contain exactly one draft creation command.\n' >&2
  violations=$((violations + 1))
fi

if ((violations > 0)); then
  printf 'Android release workflow policy failed with %d violation(s).\n' "${violations}" >&2
  exit 1
fi

printf 'Android release workflow policy passed.\n'
