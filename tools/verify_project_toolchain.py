#!/usr/bin/env python3
"""Verify the Java and SystemJ binaries frozen for the Group 6 project."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = REPO_ROOT / "toolchain" / "systemj-project.sha256"
EXPECTED_JAVA_VERSION = 'openjdk version "1.8.0_502"'
EXPECTED_JAVAC_VERSION = "javac 1.8.0_502"


def locked_jars() -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        LOCK_FILE.read_text(encoding="ascii").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split(None, 1)
        if len(fields) != 2:
            raise ValueError(f"Invalid lock entry at line {line_number}")
        digest, filename = fields
        result[filename.strip()] = digest.lower()
    return result


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_version(executable: Path) -> str:
    completed = subprocess.run(
        [str(executable), "-version"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        universal_newlines=True,
    )
    return completed.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--java-home",
        default=os.environ.get("JAVA_HOME"),
        help="Temurin 8u502 home directory; defaults to JAVA_HOME",
    )
    parser.add_argument(
        "--systemj-lib",
        default=os.environ.get("SYSTEMJ_LIB_DIR"),
        help="SystemJ JAR directory; defaults to SYSTEMJ_LIB_DIR",
    )
    args = parser.parse_args()
    if not args.java_home or not args.systemj_lib:
        parser.error("set JAVA_HOME and SYSTEMJ_LIB_DIR or pass both options")

    java_home = Path(args.java_home).expanduser()
    lib_dir = Path(args.systemj_lib).expanduser()
    executable_suffix = ".exe" if os.name == "nt" else ""
    java = java_home / "bin" / ("java" + executable_suffix)
    javac = java_home / "bin" / ("javac" + executable_suffix)
    errors: list[str] = []

    java_version = command_version(java) if java.is_file() else "unavailable"
    javac_version = command_version(javac) if javac.is_file() else "unavailable"
    if not java.is_file():
        errors.append(f"java executable not found: {java}")
    elif EXPECTED_JAVA_VERSION not in java_version:
        errors.append(f"Java mismatch: {java_version.splitlines()[0]}")
    if not javac.is_file():
        errors.append(f"javac executable not found: {javac}")
    elif EXPECTED_JAVAC_VERSION not in javac_version:
        errors.append(f"javac mismatch: {javac_version}")

    expected = locked_jars()
    if not lib_dir.is_dir():
        errors.append(f"SystemJ library directory not found: {lib_dir}")
    else:
        actual_names = {path.name for path in lib_dir.glob("*.jar")}
        for filename in sorted(set(expected) - actual_names):
            errors.append(f"missing JAR: {filename}")
        for filename in sorted(actual_names - set(expected)):
            errors.append(f"unexpected JAR: {filename}")
        for filename in sorted(set(expected) & actual_names):
            if sha256(lib_dir / filename) != expected[filename]:
                errors.append(f"checksum mismatch: {filename}")

    if errors:
        print("PROJECT_TOOLCHAIN_MISMATCH", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("PROJECT_TOOLCHAIN_OK")
    print(java_version.splitlines()[0])
    print(javac_version)
    print(f"SystemJ JARs verified: {len(expected)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
