#!/usr/bin/env python3
"""Build, test and launch the six-runtime SystemJ simulation (Python stdlib only)."""
from __future__ import annotations

import argparse
from datetime import datetime
import os
import re
from pathlib import Path
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAVA = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot")
DEFAULT_LIB = Path(r"D:\Auckland_University\COMPSYS_704\Lab\Lab3\COMPSYS704_Lab_3\lib")
CONFIGS = [
    ("m2", "machines/transfer/member2_system.xml"),
    ("m3", "machines/rotary_lid/member3_system.xml"),
    ("m4", "machines/filling_capping/member4_simulation.xml"),
    ("visualisation", "visualisation/abs_visualisation_plant.xml"),
    ("pos", "xuqi_pos/pos.xml"),
    ("coordinator", "xuqi_coordinator/coordinator.xml"),
]


def toolchain(args):
    java_home = Path(args.java_home)
    lib = Path(args.systemj_lib)
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    for path in (java, javac, lib):
        if not path.exists():
            raise RuntimeError(f"Missing toolchain path: {path}; set --java-home / --systemj-lib")
    subprocess.run([sys.executable, str(ROOT / "tools/verify_project_toolchain.py"),
                    "--java-home", str(java_home), "--systemj-lib", str(lib)],
                   cwd=ROOT, check=True)
    return java, javac, lib


def build(args):
    java, javac, lib = toolchain(args)
    generated = ROOT / "build/generated"
    classes = ROOT / "build/classes"
    generated.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    # Build only source directories; never accidentally compile old generated output.
    folders = ("common", "machines", "tests", "integration", "visualisation",
               "xuqi_pos", "xuqi_coordinator")
    sysj = sorted(p for folder in folders for p in (ROOT / folder).rglob("*.sysj"))
    handwritten = sorted(p for folder in folders for p in (ROOT / folder).rglob("*.java"))
    generated_files = []
    for index, source in enumerate(sysj, 1):
        print(f"SystemJ {index}/{len(sysj)}: {source.relative_to(ROOT)}", flush=True)
        subprocess.run([str(java), "-cp", str(lib / "*"),
                        "com.systemj.compiler.JavaPrettyPrinter", "-d", str(generated),
                        "--nojavac", "--silence", str(source)], cwd=ROOT, check=True)
    generated_files = sorted(generated.glob("*.java"))
    argfile = ROOT / "build/javac-sources.txt"
    argfile.write_text("\n".join('"' + str(p).replace("\\", "/") + '"'
                                  for p in handwritten + generated_files), encoding="utf-8")
    subprocess.run([str(javac), "-encoding", "UTF-8", "-cp", str(lib / "*"),
                    "-d", str(classes), "@" + str(argfile)], cwd=ROOT, check=True)
    print(f"BUILD PASS: {len(sysj)} SystemJ, {len(handwritten)} Java sources", flush=True)


def test(args):
    if not args.no_build:
        build(args)
    java, _, lib = toolchain(args)
    cp = str(ROOT / "build/classes") + os.pathsep + str(lib / "*")
    tests = sorted({p.stem for folder in ("tests", "integration", "machines")
                    for p in (ROOT / folder).rglob("*SelfTest.java")})
    tests.append("FaultToleranceEvaluation")
    for name in tests:
        print(f"TEST {name}", flush=True)
        subprocess.run([str(java), "-Djava.awt.headless=true", "-cp", cp, name],
                       cwd=ROOT, check=True, timeout=90)
    subprocess.run([sys.executable, "tools/validate_integration.py"], cwd=ROOT, check=True)
    print(f"TEST PASS: {len(tests)} executable suites", flush=True)


def run(args):
    if not args.no_build:
        build(args)
    java, _, lib = toolchain(args)
    cp = str(ROOT / "build/classes") + os.pathsep + str(lib / "*")
    run_dir = ROOT / "build/runs" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    run_dir.mkdir(parents=True)
    configs = []
    ports = set()
    ET.register_namespace("", "http://systemjtechnology.com")
    for name, relative in CONFIGS:
        tree = ET.parse(ROOT / relative)
        for node in tree.iter():
            if "Port" in node.attrib:
                mapped = int(node.attrib["Port"]) + args.port_offset
                if not 1024 <= mapped <= 65535:
                    raise RuntimeError(f"Invalid remapped port {mapped}")
                node.set("Port", str(mapped))
                if node.tag.rsplit("}", 1)[-1] == "iSignal":
                    ports.add(mapped)
        target = run_dir / (name + ".xml")
        tree.write(target, encoding="utf-8", xml_declaration=True)
        configs.append((name, target))
    for port in sorted(ports):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError as error:
                raise RuntimeError(f"Port {port} is occupied. Stop the old project run or choose --port-offset 20000") from error
    processes = []
    handles = []
    print(f"Run logs: {run_dir}\nPress Ctrl+C in this terminal to stop every runtime.", flush=True)
    try:
        for name, config in configs:
            options = [f"-Djava.awt.headless={str(args.headless).lower()}"]
            if name == "pos" and args.order:
                options += ["-Dabs.pos.testOrder=" + args.order,
                            "-Dabs.pos.testOrderDelayMillis=10000",
                            f"-Dabs.pos.testOrderCount={args.order_count}",
                            "-Dabs.pos.testOrderIntervalMillis=4000"]
            if name == "pos" and args.reset_after is not None:
                options.append(f"-Dabs.pos.testResetDelayMillis={int(args.reset_after * 1000)}")
            output = open(run_dir / (name + ".out.log"), "w", encoding="utf-8")
            errors = open(run_dir / (name + ".err.log"), "w", encoding="utf-8")
            handles += [output, errors]
            child = subprocess.Popen([str(java)] + options + ["-cp", cp, "com.systemj.SystemJRunner", str(config)],
                                     cwd=ROOT, stdout=output, stderr=errors,
                                     creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            processes.append((name, child))
            print(f"Started {name}, PID {child.pid}", flush=True)
            time.sleep(0.5)
        started = time.monotonic()
        while args.duration is None or time.monotonic() - started < args.duration:
            for name, child in processes:
                if child.poll() is not None:
                    raise RuntimeError(f"{name} exited with {child.returncode}; see {run_dir}")
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("Stopping project runtimes...", flush=True)
    finally:
        for _, child in processes:
            if child.poll() is None:
                child.terminate()
        for _, child in processes:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        for handle in handles:
            handle.close()
        print(f"Stopped. Evidence retained in {run_dir}", flush=True)
        for name, _ in processes:
            out = (run_dir / (name + ".out.log")).read_text(encoding="utf-8", errors="replace")
            err = (run_dir / (name + ".err.log")).read_text(encoding="utf-8", errors="replace")
            for line in out.splitlines():
                if any(key in line for key in ("received completion:", "received system reset completion:", "SYSTEM_RESET_COMPLETE attempt=1", "Reset complete", "[M4-SIM] batch accepted", "[M2-RESET]", "[M3-RESET]", "[M4-RESET]")):
                    print(name + ": " + line)
            if err.strip():
                print(f"{name}: stderr contains {len(err.splitlines())} lines; inspect {name}.err.log")

    if args.expect_completions is not None or args.expect_reset or args.expect_twins:
        pos_log = (run_dir / "pos.out.log").read_text(encoding="utf-8", errors="replace")
        coord_log = (run_dir / "coordinator.out.log").read_text(encoding="utf-8", errors="replace")
        viz_log = (run_dir / "visualisation.out.log").read_text(encoding="utf-8", errors="replace")
        completions = set(re.findall(r"received completion: orderId=([^,\s]+), status=COMPLETED", pos_log))
        checks = []
        if args.expect_completions is not None:
            checks.append((len(completions) == args.expect_completions,
                           f"POS completed {len(completions)} distinct orders (expected {args.expect_completions})"))
        if args.expect_reset:
            checks.append(("received system reset completion:" in pos_log and
                           "m2Ack=true m3Ack=true m4Ack=true" in coord_log,
                           "reset reached POS after all three member ACKs"))
        if args.expect_twins:
            snapshots = re.findall(r"\[VIZ-TWIN-DATA\] (V2\|TWIN\|[^\r\n]+)", viz_log)
            final = snapshots[-1].split("|") if snapshots else []
            complete = len(final) == 9 and final[4] != "W=0" and int(final[5][2:]) >= 4 and final[6] == "REJECTED=0"
            if complete:
                complete = all(row.split(",")[1] == "COMPLETE" for row in final[7][len("WORKPIECES="):].split(";"))
            checks.append((complete, "final visualization has confirmed COMPLETE workpieces, resource twins and no rejected updates"))
        checks.append((all(not (run_dir / (name + ".err.log")).read_text(encoding="utf-8", errors="replace").strip()
                           for name, _ in CONFIGS), "all six runtime stderr logs are empty"))
        for okay, description in checks:
            print(("PASS: " if okay else "FAIL: ") + description)
        if not all(okay for okay, _ in checks):
            raise RuntimeError(f"Live acceptance failed; see {run_dir}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "test", "run"))
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    parser.add_argument("--java-home", default=os.environ.get("PROJECT_JAVA_HOME", str(DEFAULT_JAVA)))
    parser.add_argument("--systemj-lib", default=os.environ.get("SYSTEMJ_LIB", str(DEFAULT_LIB)))
    parser.add_argument("--no-build", action="store_true", help="Use existing build/classes")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--port-offset", type=int, default=10000)
    parser.add_argument("--order", help="Automatic POS order payload (V1 or V2)")
    parser.add_argument("--order-count", type=int, default=1)
    parser.add_argument("--reset-after", type=float, help="Automatic POS reset after startup, seconds")
    parser.add_argument("--duration", type=float, help="Stop after this many seconds; default until Ctrl+C")
    parser.add_argument("--expect-completions", type=int, help="Fail unless POS receives exactly N distinct order completions")
    parser.add_argument("--expect-reset", action="store_true", help="Require the real three-member reset ACK barrier and POS completion")
    parser.add_argument("--expect-twins", action="store_true", help="Require live workpiece AND resource rows at the visualization")
    args = parser.parse_args()
    globals()["ROOT"] = args.repo_root.resolve()
    try:
        {"build": build, "test": test, "run": run}[args.action](args)
        return 0
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
