#!/usr/bin/env python3
"""Validate the canonical Group 6 topology without running SystemJ."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "integration" / "system-manifest.json"


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def load_clock_domains(config_path: Path) -> dict[str, dict[str, object]]:
    tree = ET.parse(config_path)
    result: dict[str, dict[str, object]] = {}
    for element in tree.getroot().iter():
        if local_name(element.tag) != "ClockDomain":
            continue
        name = element.attrib["Name"]
        inputs: set[str] = set()
        input_ports: set[int] = set()
        for child in element:
            if local_name(child.tag) == "iSignal":
                inputs.add(child.attrib["Name"])
                input_ports.add(int(child.attrib["Port"]))
        result[name] = {"inputs": inputs, "ports": input_ports}
    return result


def main() -> int:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    errors: list[str] = []
    warnings: list[str] = []

    receiver_by_name = {
        item["clockDomain"]: item for item in manifest["receivers"]
    }
    port_owners: dict[int, str] = {}
    for item in manifest["receivers"]:
        port = int(item["port"])
        previous = port_owners.get(port)
        if previous is not None and previous != item["clockDomain"]:
            errors.append(
                f"port {port} assigned to both {previous} and "
                f"{item['clockDomain']}"
            )
        port_owners[port] = item["clockDomain"]

    discovered: dict[str, dict[str, object]] = {}
    for relative in manifest["canonicalConfigs"]:
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing canonical XML: {relative}")
            continue
        for name, details in load_clock_domains(path).items():
            if name in discovered:
                errors.append(
                    f"Clock Domain {name} appears in multiple canonical XMLs"
                )
            discovered[name] = details

    for name, item in receiver_by_name.items():
        status = item["status"]
        if status == "implemented" and name not in discovered:
            errors.append(
                f"implemented receiver {name} is absent from canonical XML"
            )
        elif status in {"pending", "proposed"} and name not in discovered:
            warnings.append(
                f"{status}: {item['owner']} must supply {name}:{item['port']}"
            )

    for name, details in discovered.items():
        item = receiver_by_name.get(name)
        if item is None:
            errors.append(f"XML declares unregistered receiver {name}")
            continue
        ports = details["ports"]
        expected = int(item["port"])
        if ports and ports != {expected}:
            errors.append(
                f"{name} input ports {sorted(ports)} do not match {expected}"
            )

    for name, required_inputs in manifest[
        "requiredImplementedInputs"
    ].items():
        actual = discovered.get(name, {}).get("inputs", set())
        missing = set(required_inputs) - set(actual)
        if missing:
            errors.append(
                f"{name} missing inputs: {', '.join(sorted(missing))}"
            )

    expected_codes = {
        "0": "IDLE",
        "1": "READY",
        "2": "BUSY",
        "3": "DONE",
        "4": "FAULT",
    }
    if manifest["statusCodes"] != expected_codes:
        errors.append("status-code table is not the frozen 0..4 contract")

    print("Integration skeleton validation")
    print(f"  canonical Clock Domains: {len(discovered)}")
    print(f"  registered receivers: {len(receiver_by_name)}")
    for warning in warnings:
        print(f"  WARNING: {warning}")
    for error in errors:
        print(f"  ERROR: {error}")
    if errors:
        print(f"FAILED with {len(errors)} error(s)")
        return 1
    print(f"PASS with {len(warnings)} peer implementation warning(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
