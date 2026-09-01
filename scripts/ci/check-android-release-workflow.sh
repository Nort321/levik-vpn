#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/android-release.yml"
readonly PUBLISH_WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/android-publish.yml"
readonly CI_WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/android-ci.yml"
readonly SOURCE_BUNDLE_SCRIPT="${REPOSITORY_ROOT}/scripts/release/build-corresponding-source.sh"
readonly NATIVE_SBOM_SCRIPT="${REPOSITORY_ROOT}/scripts/release/generate-native-sbom.py"

if [[ ! -f "${WORKFLOW}" || -L "${WORKFLOW}" ||
      ! -f "${PUBLISH_WORKFLOW}" || -L "${PUBLISH_WORKFLOW}" ||
      ! -f "${CI_WORKFLOW}" || -L "${CI_WORKFLOW}" ||
      ! -f "${SOURCE_BUNDLE_SCRIPT}" || -L "${SOURCE_BUNDLE_SCRIPT}" ||
      ! -f "${NATIVE_SBOM_SCRIPT}" || -L "${NATIVE_SBOM_SCRIPT}" ]]; then
  printf 'ERROR: Android CI/release/publish or source bundle policy is missing or unsafe.\n' >&2
  exit 1
fi

required_literals=(
  'workflow_dispatch:'
  'environment: production-release'
  'cancel-in-progress: false'
  'signing_identity_decision:'
  'release_scope:'
  'direct-only'
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
  'generate-direct-release-feed.py'
  'validate-play-bundle-metadata.sh'
  'generate-native-sbom.py'
  '--relay-jni-directory levik_whitelist_relay/build/android/jniLibs'
  'go-version: "1.26.5"'
  'ndk_version="29.0.14206865"'
  'build-corresponding-source.sh'
  'release-provenance.json'
  'artifactChecksumsSha256'
  'openssl dgst -sha256'
  'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'
  'android-production-release-${{ inputs.version_name }}'
  'retention-days: 1'
  'gh release create'
  '"${release_apk}"'
  '--notes-file "${release_notes}"'
  '--draft'
  'Nort321/levik-vpn'
)

violations=0
for literal in "${required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${WORKFLOW}" >/dev/null; then
    printf 'ERROR: Android release workflow is missing required gate: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

ci_required_literals=(
  'go-version: "1.26.5"'
  'ndk_version="29.0.14206865"'
  'assembleDirectDebug assemblePlayDebug'
)
for literal in "${ci_required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${CI_WORKFLOW}" >/dev/null; then
    printf 'ERROR: Android CI workflow is missing required native build gate: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

source_bundle_required_literals=(
  'GOFLAGS=-mod=readonly'
  'go-modules-relay-server.json'
  'go-modules-relay-android-client.json'
  'go-modules-relay-node-agent.json'
  'liblevikrelay-${relay_abi}-build-info.txt'
  'RELAY_ANET_COMMIT'
  'github.com/wlynxg/anet'
)
for literal in "${source_bundle_required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${SOURCE_BUNDLE_SCRIPT}" >/dev/null; then
    printf 'ERROR: corresponding-source bundle is missing relay source evidence: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

sbom_required_literals=(
  'ANET_PATCH_VERSION = "v0.0.5+levik.1"'
  'levik.go.replacement'
  'BSD-3-Clause'
)
for literal in "${sbom_required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${NATIVE_SBOM_SCRIPT}" >/dev/null; then
    printf 'ERROR: native SBOM is missing local anet provenance: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

publish_required_literals=(
  'release-provenance.json'
  'release-provenance.json.sig'
  'artifactChecksumsSha256'
  'openssl dgst -sha256'
  'sha256sum --check --strict ARTIFACT-SHA256SUMS'
  'gh run download "${release_run_id}"'
  'published GitHub release must contain only the Direct APK'
  'release provenance mismatch'
)
for literal in "${publish_required_literals[@]}"; do
  if ! grep -F -- "${literal}" "${PUBLISH_WORKFLOW}" >/dev/null; then
    printf 'ERROR: Android publish workflow is missing required provenance gate: %s\n' "${literal}" >&2
    violations=$((violations + 1))
  fi
done

if grep -F -- 'gh attestation' "${WORKFLOW}" "${PUBLISH_WORKFLOW}" >/dev/null; then
  printf 'ERROR: release provenance must use the repository-defined signed provenance contract.\n' >&2
  violations=$((violations + 1))
fi

if grep -F -- 'github.event.repository.private' "${WORKFLOW}" "${PUBLISH_WORKFLOW}" >/dev/null; then
  printf 'ERROR: production workflows must support the canonical public repository.\n' >&2
  violations=$((violations + 1))
fi

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
