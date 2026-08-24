#!/usr/bin/env python3

"""Generate the bounded public metadata feed consumed by the Direct app."""

from __future__ import annotations

import argparse
import json
import pathlib
import re


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
    args = parser.parse_args()

    if not VERSION_PATTERN.fullmatch(args.version):
        raise SystemExit("version must be stable semantic version X.Y.Z")

    directory = args.output_directory.resolve(strict=True)
    tag = f"v{args.version}"
    apk_name = f"LevikVPN-direct-{args.version}.apk"
    asset_names = ("update.json", "update.json.sig", apk_name)
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

    feed = {
        "schemaVersion": 1,
        "channel": "stable",
        "tag_name": tag,
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
