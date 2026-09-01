#!/usr/bin/env python3

"""Generate CycloneDX inventory for every native binary shipped by Direct Android."""

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
RELAY_LIBRARY_NAME = "liblevikrelay.so"
RELAY_COMPONENT_VERSION = "levik-relay-v1"
RELAY_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ANET_MODULE = "github.com/wlynxg/anet"
ANET_VERSION = "v0.0.5"
ANET_PATCH_VERSION = "v0.0.5+levik.1"
ANET_REPLACEMENT = "./third_party/anet"
ANET_COMMIT = "839bc3a920f1b87dd3ce1386e425aa5ef2e69d24"
ANET_SOURCE_URL = f"https://github.com/wlynxg/anet/tree/{ANET_COMMIT}"
GoModule = tuple[str, str, str | None, str | None]


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True, type=pathlib.Path)
    parser.add_argument("--relay-jni-directory", required=True, type=pathlib.Path)
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


def parse_go_build_info(
    output: str,
) -> tuple[str, list[GoModule]]:
    first_line = output.splitlines()[0] if output.splitlines() else ""
    version_match = re.search(r":\s+(go[0-9]+(?:\.[0-9]+)+)$", first_line)
    go_version = version_match.group(1) if version_match else ""
    modules: list[list[str | None]] = []
    for raw_line in output.splitlines():
        line = raw_line.lstrip()
        if line.startswith("build\t-go="):
            go_version = line.removeprefix("build\t-go=")
        elif line.startswith("dep\t"):
            fields = line.split("\t")
            if len(fields) not in (3, 4) or not fields[1] or not fields[2]:
                raise SystemExit("malformed embedded Go module metadata")
            modules.append([fields[1], fields[2], fields[3] if len(fields) == 4 else None, None])
        elif line.startswith("=>\t"):
            fields = [field for field in line.split("\t") if field]
            if not modules or len(fields) < 2 or modules[-1][3] is not None:
                raise SystemExit("malformed embedded Go replacement metadata")
            modules[-1][3] = fields[1]
    if not go_version or not modules:
        raise SystemExit("embedded Go build metadata is incomplete")
    normalized = {tuple(module) for module in modules}
    return go_version, sorted(normalized, key=lambda module: tuple(value or "" for value in module))


def inspect_go_binary(
    library_path: pathlib.Path,
    go_command: str,
) -> tuple[str, list[GoModule]]:
    require_regular_file(library_path, "native Go binary")
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
    return parse_go_build_info(result.stdout)


def go_build_info(
    aar_path: pathlib.Path,
    go_command: str,
) -> tuple[str, list[GoModule]]:
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

        return inspect_go_binary(library_path, go_command)


def relay_build_info(
    relay_jni_directory: pathlib.Path,
    go_command: str,
) -> tuple[list[tuple[str, str, str]], list[GoModule]]:
    if not relay_jni_directory.is_dir() or relay_jni_directory.is_symlink():
        raise SystemExit(
            f"relay JNI directory is missing or unsafe: {relay_jni_directory}",
        )
    artifacts: list[tuple[str, str, str]] = []
    common_modules: list[GoModule] | None = None
    for abi in RELAY_ABIS:
        library_path = relay_jni_directory / abi / RELAY_LIBRARY_NAME
        go_version, modules = inspect_go_binary(library_path, go_command)
        if common_modules is None:
            common_modules = modules
        elif modules != common_modules:
            raise SystemExit("relay Go module metadata differs between Android ABIs")
        artifacts.append((abi, sha256_file(library_path), go_version))
    return artifacts, common_modules or []


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
    modules: list[GoModule],
    relay_artifacts: list[tuple[str, str, str]],
    relay_modules: list[GoModule],
) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, list[str]]]:
    native = source_lock["nativeArtifact"]
    if native.get("sha256") != aar_sha256 or not SHA256_PATTERN.fullmatch(aar_sha256):
        raise SystemExit("libXray AAR SHA-256 does not match the native source lock")
    if native.get("embeddedGoVersion") != embedded_go_version:
        raise SystemExit("embedded Go version does not match the native source lock")
    expected_go_version = str(native.get("embeddedGoVersion", ""))
    if len(relay_artifacts) != len(RELAY_ABIS):
        raise SystemExit("relay native inventory does not cover every required Android ABI")
    if any(go_version != expected_go_version for _, _, go_version in relay_artifacts):
        raise SystemExit("relay embedded Go version does not match the native source lock")

    source_by_module = {
        "github.com/xtls/xray-core": "Xray-core",
        "github.com/sagernet/sing": "sing",
        "github.com/sagernet/sing-shadowsocks": "sing-shadowsocks",
    }
    archive_by_name = {
        archive.get("name"): archive for archive in source_lock["sourceArchives"]
    }
    relay_source = archive_by_name.get("WDTT-Plus", {})
    go_source = archive_by_name.get("Go toolchain source", {})
    if relay_source.get("license") != "GPL-3.0-only":
        raise SystemExit("native source lock does not identify the relay GPL source")
    if go_source.get("version") != expected_go_version:
        raise SystemExit("native source lock does not identify the relay Go toolchain source")
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
    component_refs = {aar_ref}
    dependency_graph: dict[str, list[str]] = {}

    def add_go_modules(
        embedded_modules: list[GoModule],
    ) -> list[str]:
        dependency_refs: list[str] = []
        for module, version, module_sum, replacement in embedded_modules:
            is_local_anet = module == ANET_MODULE and replacement == ANET_REPLACEMENT
            component_version = ANET_PATCH_VERSION if is_local_anet else version
            module_ref = component_ref("go", module, component_version)
            dependency_refs.append(module_ref)
            if module_ref in component_refs:
                continue
            source = archive_by_name.get(source_by_module.get(module, ""), {})
            component: dict[str, Any] = {
                "type": "library",
                "bom-ref": module_ref,
                "group": module.rpartition("/")[0],
                "name": module.rpartition("/")[2],
                "version": component_version,
                "purl": go_purl(module, component_version),
                "properties": [{"name": "levik.go.modulePath", "value": module}],
            }
            if module_sum:
                component["properties"].append(
                    {"name": "levik.go.moduleSum", "value": module_sum},
                )
            if replacement:
                component["properties"].append(
                    {"name": "levik.go.replacement", "value": replacement},
                )
            if module == ANET_MODULE:
                component["licenses"] = [{"license": {"id": "BSD-3-Clause"}}]
                component["externalReferences"] = [
                    {"type": "distribution", "url": ANET_SOURCE_URL},
                ]
            if is_local_anet:
                component["properties"].extend(
                    [
                        {"name": "levik.go.upstreamVersion", "value": ANET_VERSION},
                        {"name": "levik.source.commit", "value": ANET_COMMIT},
                        {
                            "name": "levik.source.localPath",
                            "value": "levik_whitelist_relay/fork/wdtt-plus-v15/go_client/third_party/anet",
                        },
                    ],
                )
            if source.get("license"):
                component["licenses"] = [{"license": {"id": source["license"]}}]
            if source.get("url"):
                component["externalReferences"] = [
                    {"type": "distribution", "url": source["url"]},
                ]
            components.append(component)
            component_refs.add(module_ref)
        return sorted(set(dependency_refs))

    dependency_graph[aar_ref] = add_go_modules(modules)
    relay_anet = [module for module in relay_modules if module[0] == ANET_MODULE]
    if relay_anet != [(ANET_MODULE, ANET_VERSION, None, ANET_REPLACEMENT)]:
        raise SystemExit("relay anet dependency is not the pinned local fork")
    relay_dependency_refs = add_go_modules(relay_modules)
    relay_component_refs: list[str] = []
    for abi, relay_sha256, relay_go_version in relay_artifacts:
        if abi not in RELAY_ABIS or not SHA256_PATTERN.fullmatch(relay_sha256):
            raise SystemExit("relay native inventory contains an invalid artifact")
        relay_name = f"{RELAY_LIBRARY_NAME}:{abi}"
        relay_ref = component_ref("native", relay_name, RELAY_COMPONENT_VERSION)
        relay_component_refs.append(relay_ref)
        components.append(
            {
                "type": "application",
                "bom-ref": relay_ref,
                "group": "com.leviknet.relay",
                "name": relay_name,
                "version": RELAY_COMPONENT_VERSION,
                "hashes": [{"alg": "SHA-256", "content": relay_sha256}],
                "licenses": [{"license": {"id": relay_source["license"]}}],
                "purl": (
                    "pkg:generic/liblevikrelay@levik-relay-v1?arch="
                    + urllib.parse.quote(abi, safe="._-")
                ),
                "properties": [
                    {"name": "levik.native.abi", "value": abi},
                    {
                        "name": "levik.native.archivePath",
                        "value": f"lib/{abi}/{RELAY_LIBRARY_NAME}",
                    },
                    {
                        "name": "levik.native.embeddedGoVersion",
                        "value": relay_go_version,
                    },
                ],
            },
        )
        component_refs.add(relay_ref)
        dependency_graph[relay_ref] = relay_dependency_refs

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
        component_refs.add(archive_ref)

    metadata_component = {
        "type": "application",
        "bom-ref": "urn:levik:android:native-inventory",
        "group": "com.leviknet",
        "name": "Levik VPN Android native inventory",
        "version": str(native["version"]),
    }
    dependency_graph[metadata_component["bom-ref"]] = [aar_ref, *relay_component_refs]
    return metadata_component, components, dependency_graph


def write_json(
    path: pathlib.Path,
    metadata_component: dict[str, Any],
    components: list[dict[str, Any]],
    dependency_graph: dict[str, list[str]],
) -> None:
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": CYCLONEDX_SPEC_VERSION,
        "version": 1,
        "metadata": {"component": metadata_component},
        "components": components,
        "dependencies": [
            {
                "ref": metadata_component["bom-ref"],
                "dependsOn": dependency_graph.get(metadata_component["bom-ref"], []),
            },
            *(
                {
                    "ref": component["bom-ref"],
                    "dependsOn": dependency_graph.get(component["bom-ref"], []),
                }
                for component in components
            ),
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
    dependency_graph: dict[str, list[str]],
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
    for component in (metadata_component, *components):
        dependency = ET.SubElement(
            dependencies,
            f"{{{namespace}}}dependency",
            {"ref": component["bom-ref"]},
        )
        for dependency_ref in dependency_graph.get(component["bom-ref"], []):
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
    relay_artifacts, relay_modules = relay_build_info(
        arguments.relay_jni_directory,
        arguments.go,
    )
    metadata_component, components, dependencies = make_inventory(
        source_lock,
        aar_sha256,
        embedded_go_version,
        modules,
        relay_artifacts,
        relay_modules,
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
    print(
        "Generated native CycloneDX inventory for "
        f"{len(modules)} libXray and {len(relay_modules)} relay Go modules.",
    )


if __name__ == "__main__":
    main()
