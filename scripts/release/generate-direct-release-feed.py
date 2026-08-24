#!/usr/bin/env python3

"""Generate the bounded public metadata feed consumed by the Direct app."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import time


VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
PUBLIC_BASE_URL = "https://leviknet.com/downloads/android/stable"


def regular_file(directory: pathlib.Path, name: str) -> pathlib.Path:
    path = directory / name
    if path.is_symlink() or not path.is_file():
        raise SystemExit(f"required release asset is missing or unsafe: {name}")
    return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-directory", required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--generated-at", type=int)
    parser.add_argument("--valid-for-seconds", type=int, default=48 * 60 * 60)
    parser.add_argument("--metadata-only", action="store_true")
    args = parser.parse_args()

    if not VERSION_PATTERN.fullmatch(args.version):
        raise SystemExit("version must be stable semantic version X.Y.Z")

    directory = args.output_directory.resolve(strict=True)
    if args.valid_for_seconds not in range(60 * 60, 72 * 60 * 60 + 1):
        raise SystemExit("feed validity must be between one and seventy-two hours")
    generated_at = args.generated_at if args.generated_at is not None else int(time.time())
    if generated_at <= 0:
        raise SystemExit("generated-at must be a positive Unix timestamp")
    tag = f"v{args.version}"
    apk_name = f"LevikVPN-direct-{args.version}.apk"
    manifest_path = regular_file(directory, "update.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    version_code = manifest.get("versionCode")
    if not isinstance(version_code, int) or version_code <= 0:
        raise SystemExit("update manifest has an invalid versionCode")
    if manifest.get("versionName") != args.version:
        raise SystemExit("update manifest versionName does not match the feed version")
    expected_apk_url = f"{PUBLIC_BASE_URL}/{tag}/{apk_name}"
    if manifest.get("apkUrl") != expected_apk_url:
        raise SystemExit("update manifest APK URL does not match the feed version")
    apk_size = manifest.get("apkSize")
    if not isinstance(apk_size, int) or apk_size <= 0:
        raise SystemExit("update manifest has an invalid APK size")

    asset_names = ("update.json", "update.json.sig")
    assets = []
    for name in asset_names:
        path = regular_file(directory, name)
        size = path.stat().st_size
        if size <= 0:
            raise SystemExit(f"release asset is empty: {name}")
        assets.append(
            {
                "name": name,
                "size": size,
                "url": f"{PUBLIC_BASE_URL}/{tag}/{name}",
            }
        )
    if not args.metadata_only:
        apk_path = regular_file(directory, apk_name)
        if apk_path.stat().st_size != apk_size:
            raise SystemExit("release APK size does not match the signed update manifest")
    assets.append(
        {
            "name": apk_name,
            "size": apk_size,
            "url": expected_apk_url,
        }
    )

    feed = {
        "schemaVersion": 1,
        "channel": "stable",
        "tag_name": tag,
        "version_code": version_code,
        "generated_at": generated_at,
        "expires_at": generated_at + args.valid_for_seconds,
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "draft": False,
        "prerelease": False,
        "assets": assets,
    }
    output = directory / "latest.json"
    if output.exists() or output.is_symlink():
        raise SystemExit("refusing to overwrite latest.json")
    output.write_text(
        json.dumps(feed, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    main()
