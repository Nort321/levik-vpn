#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly MAX_TRACKED_FILE_BYTES=$((20 * 1024 * 1024))

usage() {
  printf 'Usage: %s [--stdin0]\n' "${0##*/}" >&2
  printf '  default: validate paths tracked by the current Git repository\n' >&2
  printf '  --stdin0: validate a NUL-delimited path list from standard input\n' >&2
}

readonly INPUT_MODE="${1:-git}"
if [[ "${INPUT_MODE}" != "git" && "${INPUT_MODE}" != "--stdin0" ]]; then
  usage
  exit 64
fi

temporary_paths=""
cleanup() {
  if [[ -n "${temporary_paths}" && -f "${temporary_paths}" ]]; then
    rm -f -- "${temporary_paths}"
  fi
}
trap cleanup EXIT

if [[ "${INPUT_MODE}" == "git" ]]; then
  if ! git -C "${REPOSITORY_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    printf 'ERROR: repository policy requires an initialized Git work tree.\n' >&2
    exit 2
  fi

  temporary_paths="$(mktemp "${TMPDIR:-/tmp}/levikvpn-policy.XXXXXX")"
  git -C "${REPOSITORY_ROOT}" ls-files -z >"${temporary_paths}"
  input_path="${temporary_paths}"
else
  input_path="/dev/stdin"
fi

violations=0

reject_path() {
  local path="$1"
  local reason="$2"
  printf 'ERROR: forbidden tracked path %q: %s\n' "${path}" "${reason}" >&2
  violations=$((violations + 1))
}

while IFS= read -r -d '' path; do
  if [[ -z "${path}" || "${path}" == /* || "${path}" == ../* || "${path}" == */../* ]]; then
    reject_path "${path}" "path is empty, absolute, or escapes the repository"
    continue
  fi

  case "${path}" in
    levik_vpn_landing|levik_vpn_landing/*|levik_vpn_bridge|levik_vpn_bridge/*)
      reject_path "${path}" "website, backend, and bridge files are outside the Android-only repository scope"
      ;;
    .env.example|.env.*.example|*.env.example|*.env.template|*/.env.example|*/.env.*.example|*/secrets/README.md|*/secrets/*.example)
      ;;
    .env|.env.*|*/.env|*/.env.*|*/secrets/*)
      reject_path "${path}" "environment or secret material"
      ;;
  esac

  case "${path}" in
    .secure-backups|.secure-backups/*|*/.secure-backups/*)
      reject_path "${path}" "local backup material"
      ;;
    primary_bot_patch|primary_bot_patch/*|*/primary_bot_patch/*|tg_inviter|tg_inviter/*|*/tg_inviter/*)
      reject_path "${path}" "historical service or account snapshot"
      ;;
    *.session|*.session-journal|*/tdata/*|tdata/*)
      reject_path "${path}" "account session state"
      ;;
    *.sqlite|*.sqlite-*|*.sqlite3|*.sqlite3-*|*.dump|*.sql.gz|*.rdb)
      reject_path "${path}" "database or backup state"
      ;;
    *.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|*.mobileprovision|*/keystore.properties|keystore.properties|*/local.properties|local.properties)
      reject_path "${path}" "private key or signing material"
      ;;
    */*service-account*.json|*service-account*.json|*/*credentials*.json|*credentials*.json|*/config.json|config.json)
      reject_path "${path}" "credential-bearing or runtime configuration"
      ;;
    *.apk|*.aab|*.apks|*.aar|*/mapping.txt|mapping.txt|*.symbols.zip)
      reject_path "${path}" "generated release artifact"
      ;;
    .DS_Store|*/.DS_Store|._*|*/._*|*.bak|*.bak-*|*.orig)
      reject_path "${path}" "local metadata or backup copy"
      ;;
    node_modules/*|*/node_modules/*|.next/*|*/.next/*|coverage/*|*/coverage/*|*.tsbuildinfo)
      reject_path "${path}" "JavaScript build or dependency output"
      ;;
    .gradle/*|*/.gradle/*|.kotlin/*|*/.kotlin/*|build/*|*/build/*|dist/*|*/dist/*|.cxx/*|*/.cxx/*|.externalNativeBuild/*|*/.externalNativeBuild/*)
      reject_path "${path}" "Android or generated build output"
      ;;
    venv/*|*/venv/*|.venv/*|*/.venv/*|__pycache__/*|*/__pycache__/*|.pytest_cache/*|*/.pytest_cache/*|.ruff_cache/*|*/.ruff_cache/*|*.pyc|*.pyo)
      reject_path "${path}" "Python environment or generated output"
      ;;
  esac

  absolute_path="${REPOSITORY_ROOT}/${path}"
  if [[ -f "${absolute_path}" && ! -L "${absolute_path}" ]]; then
    file_size="$(wc -c <"${absolute_path}")"
    file_size="${file_size//[[:space:]]/}"
    if [[ "${file_size}" =~ ^[0-9]+$ ]] && ((file_size > MAX_TRACKED_FILE_BYTES)); then
      reject_path "${path}" "file exceeds the 20 MiB source-control limit"
    fi
  fi
done <"${input_path}"

if [[ "${INPUT_MODE}" == "git" ]]; then
  required_paths=(
    .gitignore
    .gitattributes
    README.md
    LICENSE
    SECURITY.md
    CONTRIBUTING.md
    CODE_OF_CONDUCT.md
    CHANGELOG.md
    THIRD_PARTY_NOTICES.md
    scripts/ci/check-repository-policy.sh
    scripts/ci/check-action-pins.sh
    scripts/ci/check-android-release-workflow.sh
    scripts/ci/fetch-libxray.sh
    scripts/release/build-corresponding-source.sh
    scripts/release/generate-direct-update-manifest.sh
    scripts/release/levik-android-release-refresh
    scripts/release/levik-android-release.sudoers
    scripts/release/generate-native-sbom.py
    scripts/release/validate-play-bundle-metadata.sh
    release/native-sources.lock.json
    .github/workflows/android-release.yml
    .github/workflows/android-dependency-verification.yml
    levik_vpn_android/gradle.lockfile
    levik_vpn_android/app/gradle.lockfile
    levik_vpn_android/gradle/verification-metadata.xml
  )

  for required_path in "${required_paths[@]}"; do
    if ! git -C "${REPOSITORY_ROOT}" ls-files --error-unmatch -- "${required_path}" >/dev/null 2>&1; then
      printf 'ERROR: required policy file is not tracked: %s\n' "${required_path}" >&2
      violations=$((violations + 1))
    fi
  done
fi

if ((violations > 0)); then
  printf 'Repository policy failed with %d violation(s).\n' "${violations}" >&2
  exit 1
fi

printf 'Repository policy passed.\n'
