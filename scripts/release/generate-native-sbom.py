#!/usr/bin/env python3

"""Generate CycloneDX inventory for the opaque libXray AAR and embedded Go modules."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import urllib.parse
import zipfile
from typing import Any
import xml.etree.ElementTree as ET


SCHEMA_VERSION = 1
CYCLONEDX_SPEC_VERSION = "1.6"
JSON_FILENAME = "levik-vpn-native.cdx.json"
XML_FILENAME = "levik-vpn-native.cdx.xml"
GO_LIBRARY_PATH = "jni/arm64-v8a/libgojni.so"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True, type=pathlib.Path)
    parser.add_argument("--source-lock", required=True, type=pathlib.Path)
    parser.add_argument("--output-directory", required=True, type=pathlib.Path)
    parser.add_argument("--go", default="go")
    return parser.parse_args()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_regular_file(path: pathlib.Path, label: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise SystemExit(f"{label} is missing or is not a regular file: {path}")


def load_source_lock(path: pathlib.Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"invalid native source lock: {error}") from error
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit("unsupported native source lock schema")
    native = payload.get("nativeArtifact")
    archives = payload.get("sourceArchives")
    if not isinstance(native, dict) or not isinstance(archives, list) or not archives:
        raise SystemExit("native source lock is incomplete")
    return payload


def go_build_info(
    aar_path: pathlib.Path,
    go_command: str,
) -> tuple[str, list[tuple[str, str, str | None]]]:
    with tempfile.TemporaryDirectory(prefix="levikvpn-native-sbom-") as temporary:
        library_path = pathlib.Path(temporary) / "libgojni.so"
        try:
            with zipfile.ZipFile(aar_path) as archive:
                metadata = archive.getinfo(GO_LIBRARY_PATH)
                if metadata.file_size <= 0 or metadata.file_size > 512 * 1024 * 1024:
                    raise SystemExit("embedded libgojni.so size is invalid")
                with archive.open(metadata) as source, library_path.open("wb") as output:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
        except (KeyError, OSError, zipfile.BadZipFile) as error:
            raise SystemExit(f"unable to inspect libXray AAR: {error}") from error

        try:
            result = subprocess.run(
                [go_command, "version", "-m", str(library_path)],
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise SystemExit("unable to read embedded Go build metadata") from error

    first_line = result.stdout.splitlines()[0] if result.stdout.splitlines() else ""
    version_match = re.search(r":\s+(go[0-9]+(?:\.[0-9]+)+)$", first_line)
    go_version = version_match.group(1) if version_match else ""
    modules: list[tuple[str, str, str | None]] = []
    for raw_line in result.stdout.splitlines():
        line = raw_line.lstrip()
        if line.startswith("build\t-go="):
            go_version = line.removeprefix("build\t-go=")
        elif line.startswith("dep\t"):
            fields = line.split("\t")
            if len(fields) not in (3, 4) or not fields[1] or not fields[2]:
                raise SystemExit("malformed embedded Go module metadata")
            modules.append((fields[1], fields[2], fields[3] if len(fields) == 4 else None))
    if not go_version or not modules:
        raise SystemExit("embedded Go build metadata is incomplete")
    return go_version, sorted(set(modules))


def component_ref(component_type: str, name: str, version: str) -> str:
    identity = f"{component_type}:{name}:{version}".encode("utf-8")
    return "urn:levik:component:" + hashlib.sha256(identity).hexdigest()


def go_purl(module: str, version: str) -> str:
    encoded_module = "/".join(urllib.parse.quote(part, safe="._-") for part in module.split("/"))
    return f"pkg:golang/{encoded_module}@{urllib.parse.quote(version, safe='._+-')}"


def make_inventory(
    source_lock: dict[str, Any],
    aar_sha256: str,
    embedded_go_version: str,
    modules: list[tuple[str, str, str | None]],
) -> tuple[dict[str, Any], list[dict[str, Any]], list[str]]:
    native = source_lock["nativeArtifact"]
    if native.get("sha256") != aar_sha256 or not SHA256_PATTERN.fullmatch(aar_sha256):
        raise SystemExit("libXray AAR SHA-256 does not match the native source lock")
    if native.get("embeddedGoVersion") != embedded_go_version:
        raise SystemExit("embedded Go version does not match the native source lock")

    source_by_module = {
        "github.com/xtls/xray-core": "Xray-core",
        "github.com/sagernet/sing": "sing",
        "github.com/sagernet/sing-shadowsocks": "sing-shadowsocks",
    }
    archive_by_name = {
        archive.get("name"): archive for archive in source_lock["sourceArchives"]
    }
    root_license = archive_by_name.get("libXray", {}).get("license")
    aar_ref = component_ref("aar", native["name"], native["version"])
    aar_component: dict[str, Any] = {
        "type": "library",
        "bom-ref": aar_ref,
        "group": "github.com/XTLS/libXray",
        "name": native["name"],
        "version": native["version"],
        "hashes": [{"alg": "SHA-256", "content": aar_sha256}],
        "purl": f"pkg:generic/libXray@{urllib.parse.quote(native['version'], safe='._+-')}",
        "properties": [
            {"name": "levik.native.embeddedGoVersion", "value": embedded_go_version},
            {"name": "levik.native.archivePath", "value": GO_LIBRARY_PATH},
        ],
    }
    if root_license:
        aar_component["licenses"] = [{"license": {"id": root_license}}]

    components = [aar_component]
    aar_dependencies: list[str] = []
    for module, version, module_sum in modules:
        module_ref = component_ref("go", module, version)
        source = archive_by_name.get(source_by_module.get(module, ""), {})
        component: dict[str, Any] = {
            "type": "library",
            "bom-ref": module_ref,
            "group": module.rpartition("/")[0],
            "name": module.rpartition("/")[2],
            "version": version,
            "purl": go_purl(module, version),
            "properties": [{"name": "levik.go.modulePath", "value": module}],
        }
        if module_sum:
            component["properties"].append({"name": "levik.go.moduleSum", "value": module_sum})
        if source.get("license"):
            component["licenses"] = [{"license": {"id": source["license"]}}]
        if source.get("url"):
            component["externalReferences"] = [
                {"type": "distribution", "url": source["url"]},
            ]
        components.append(component)
        aar_dependencies.append(module_ref)

    for archive in source_lock["sourceArchives"]:
        required = ("name", "version", "url", "sha256", "license")
        if any(not archive.get(field) for field in required):
            raise SystemExit("native source lock contains an incomplete source archive")
        if not SHA256_PATTERN.fullmatch(str(archive["sha256"])):
            raise SystemExit("native source lock contains an invalid source digest")
        archive_ref = component_ref("source", archive["name"], archive["version"])
        properties = []
        if archive.get("commit"):
            properties.append({"name": "levik.source.commit", "value": archive["commit"]})
        components.append(
            {
                "type": "file",
                "bom-ref": archive_ref,
                "name": f"{archive['name']} source archive",
                "version": archive["version"],
                "hashes": [{"alg": "SHA-256", "content": archive["sha256"]}],
                "licenses": [{"license": {"id": archive["license"]}}],
                "externalReferences": [{"type": "distribution", "url": archive["url"]}],
                "properties": properties,
            },
        )

    metadata_component = {
        "type": "application",
        "bom-ref": "urn:levik:android:native-inventory",
        "group": "com.leviknet",
        "name": "Levik VPN Android native inventory",
        "version": str(native["version"]),
    }
    return metadata_component, components, aar_dependencies


def write_json(
    path: pathlib.Path,
    metadata_component: dict[str, Any],
    components: list[dict[str, Any]],
    aar_dependencies: list[str],
) -> None:
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": CYCLONEDX_SPEC_VERSION,
        "version": 1,
        "metadata": {"component": metadata_component},
        "components": components,
        "dependencies": [
            {"ref": components[0]["bom-ref"], "dependsOn": aar_dependencies},
            *({"ref": component["bom-ref"], "dependsOn": []} for component in components[1:]),
        ],
    }
    write_exclusive(path, (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def add_text(parent: ET.Element, name: str, value: Any, namespace: str) -> ET.Element:
    element = ET.SubElement(parent, f"{{{namespace}}}{name}")
    element.text = str(value)
    return element


def append_xml_component(parent: ET.Element, component: dict[str, Any], namespace: str) -> None:
    node = ET.SubElement(
        parent,
        f"{{{namespace}}}component",
        {"type": component["type"], "bom-ref": component["bom-ref"]},
    )
    for field in ("group", "name", "version"):
        if component.get(field):
            add_text(node, field, component[field], namespace)
    if component.get("hashes"):
        hashes = ET.SubElement(node, f"{{{namespace}}}hashes")
        for item in component["hashes"]:
            add_text(hashes, "hash", item["content"], namespace).set("alg", item["alg"])
    if component.get("licenses"):
        licenses = ET.SubElement(node, f"{{{namespace}}}licenses")
        for item in component["licenses"]:
            license_node = ET.SubElement(licenses, f"{{{namespace}}}license")
            add_text(license_node, "id", item["license"]["id"], namespace)
    if component.get("purl"):
        add_text(node, "purl", component["purl"], namespace)
    if component.get("externalReferences"):
        references = ET.SubElement(node, f"{{{namespace}}}externalReferences")
        for item in component["externalReferences"]:
            reference = ET.SubElement(
                references,
                f"{{{namespace}}}reference",
                {"type": item["type"]},
            )
            add_text(reference, "url", item["url"], namespace)
    if component.get("properties"):
        properties = ET.SubElement(node, f"{{{namespace}}}properties")
        for item in component["properties"]:
            add_text(properties, "property", item["value"], namespace).set("name", item["name"])


def write_xml(
    path: pathlib.Path,
    metadata_component: dict[str, Any],
    components: list[dict[str, Any]],
    aar_dependencies: list[str],
) -> None:
    namespace = f"http://cyclonedx.org/schema/bom/{CYCLONEDX_SPEC_VERSION}"
    ET.register_namespace("", namespace)
    root = ET.Element(f"{{{namespace}}}bom", {"version": "1"})
    metadata = ET.SubElement(root, f"{{{namespace}}}metadata")
    append_xml_component(metadata, metadata_component, namespace)
    component_nodes = ET.SubElement(root, f"{{{namespace}}}components")
    for component in components:
        append_xml_component(component_nodes, component, namespace)
    dependencies = ET.SubElement(root, f"{{{namespace}}}dependencies")
    for component in components:
        dependency = ET.SubElement(
            dependencies,
            f"{{{namespace}}}dependency",
            {"ref": component["bom-ref"]},
        )
        if component is components[0]:
            for dependency_ref in aar_dependencies:
                ET.SubElement(
                    dependency,
                    f"{{{namespace}}}dependency",
                    {"ref": dependency_ref},
                )
    ET.indent(root, space="  ")
    write_exclusive(path, ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")


def write_exclusive(path: pathlib.Path, payload: bytes) -> None:
    try:
        with path.open("xb") as output:
            output.write(payload)
    except FileExistsError as error:
        raise SystemExit(f"refusing to overwrite SBOM output: {path}") from error


def main() -> None:
    arguments = parse_arguments()
    require_regular_file(arguments.aar, "libXray AAR")
    require_regular_file(arguments.source_lock, "native source lock")
    source_lock = load_source_lock(arguments.source_lock)
    aar_sha256 = sha256_file(arguments.aar)
    embedded_go_version, modules = go_build_info(arguments.aar, arguments.go)
    metadata_component, components, dependencies = make_inventory(
        source_lock,
        aar_sha256,
        embedded_go_version,
        modules,
    )
    arguments.output_directory.mkdir(parents=True, exist_ok=True)
    write_json(
        arguments.output_directory / JSON_FILENAME,
        metadata_component,
        components,
        dependencies,
    )
    write_xml(
        arguments.output_directory / XML_FILENAME,
        metadata_component,
        components,
        dependencies,
    )
    print(f"Generated native CycloneDX inventory for {len(modules)} embedded Go modules.")


if __name__ == "__main__":
    main()
