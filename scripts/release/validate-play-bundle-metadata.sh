#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

if [[ $# -ne 4 ]]; then
  printf 'Usage: %s <play-aab> <expected-package> <expected-version-code> <expected-version-name>\n' \
    "${0##*/}" >&2
  exit 64
fi

readonly PLAY_AAB="$1"
readonly EXPECTED_PACKAGE="$2"
readonly EXPECTED_VERSION_CODE="$3"
readonly EXPECTED_VERSION_NAME="$4"

if [[ ! -f "${PLAY_AAB}" || -L "${PLAY_AAB}" ]]; then
  printf 'ERROR: Play AAB is missing or is not a regular file.\n' >&2
  exit 2
fi
for command_name in python3; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'ERROR: required command is unavailable: %s\n' "${command_name}" >&2
    exit 2
  fi
done
if [[ ! "${EXPECTED_VERSION_CODE}" =~ ^[1-9][0-9]*$ ||
      -z "${EXPECTED_PACKAGE}" || -z "${EXPECTED_VERSION_NAME}" ]]; then
  printf 'ERROR: expected Play metadata is invalid.\n' >&2
  exit 64
fi

python3 - \
  "${PLAY_AAB}" \
  "${EXPECTED_PACKAGE}" \
  "${EXPECTED_VERSION_CODE}" \
  "${EXPECTED_VERSION_NAME}" <<'PY'
import sys
import zipfile

aab_path, expected_package, expected_code, expected_name = sys.argv[1:]
manifest_entry = "base/manifest/AndroidManifest.xml"


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
    raise ValueError("oversized protobuf varint")


def fields(data: bytes):
    offset = 0
    while offset < len(data):
        key, offset = read_varint(data, offset)
        number, wire_type = key >> 3, key & 7
        if number <= 0:
            raise ValueError("invalid protobuf field number")
        if wire_type == 0:
            value, offset = read_varint(data, offset)
        elif wire_type == 1:
            if offset + 8 > len(data):
                raise ValueError("truncated fixed64 protobuf field")
            value, offset = data[offset : offset + 8], offset + 8
        elif wire_type == 2:
            size, offset = read_varint(data, offset)
            if size > len(data) - offset:
                raise ValueError("truncated length-delimited protobuf field")
            value, offset = data[offset : offset + size], offset + size
        elif wire_type == 5:
            if offset + 4 > len(data):
                raise ValueError("truncated fixed32 protobuf field")
            value, offset = data[offset : offset + 4], offset + 4
        else:
            raise ValueError(f"unsupported protobuf wire type: {wire_type}")
        yield number, wire_type, value


def one_message(data: bytes, number: int) -> bytes:
    values = [value for field, wire, value in fields(data) if field == number and wire == 2]
    if len(values) != 1:
        raise ValueError(f"expected exactly one protobuf message field {number}")
    return values[0]


def optional_text(data: bytes, number: int) -> str:
    values = [value for field, wire, value in fields(data) if field == number and wire == 2]
    if len(values) > 1:
        raise ValueError(f"duplicate protobuf text field {number}")
    return values[0].decode("utf-8") if values else ""


try:
    with zipfile.ZipFile(aab_path) as bundle:
        entry = bundle.getinfo(manifest_entry)
        if entry.file_size <= 0 or entry.file_size > 2 * 1024 * 1024:
            raise ValueError("base manifest size is outside the allowed range")
        manifest = bundle.read(entry)

    # AAB manifests use the stable AAPT2 Resources.XmlNode protobuf schema:
    # XmlNode.element = 1, XmlElement.attribute = 4, and
    # XmlAttribute namespace/name/value = 1/2/3.
    element = one_message(manifest, 1)
    attributes: dict[tuple[str, str], str] = {}
    for field, wire, attribute in fields(element):
        if field != 4 or wire != 2:
            continue
        key = (optional_text(attribute, 1), optional_text(attribute, 2))
        if not key[1] or key in attributes:
            raise ValueError("invalid or duplicate manifest attribute")
        attributes[key] = optional_text(attribute, 3)
except (KeyError, OSError, ValueError, zipfile.BadZipFile, UnicodeDecodeError) as error:
    raise SystemExit(f"invalid Play AAB manifest: {error}")

android_namespace = "http://schemas.android.com/apk/res/android"
actual = (
    attributes.get(("", "package")),
    attributes.get((android_namespace, "versionCode")),
    attributes.get((android_namespace, "versionName")),
)
expected = (expected_package, expected_code, expected_name)
if actual != expected:
    raise SystemExit(
        "Play AAB metadata does not match release inputs: "
        f"expected={expected!r}, actual={actual!r}"
    )
PY

printf 'Validated Play AAB package and version metadata.\n'
