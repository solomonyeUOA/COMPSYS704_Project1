# Member 3: Rotary Table, Lid Loader and Fault Supervisor

This folder contains the M3 SystemJ Controllers, simulated Plant models,
fault-tolerance IP extension, visualisation and deterministic tests. The
implementation follows `COMPSYS704_Interface_Interim_Final.xlsx` V2.1.

## Frozen boundaries

| Owner | Clock domain | Receiver port | Frozen status interface |
| --- | --- | ---: | --- |
| M3 | `RotaryTableControllerCD` | 11003 | `ROTARY_STATUS_REQUEST` / `ROTARY_STATUS` |
| M3 | `LidLoaderControllerCD` | 11006 | `LID_STATUS_REQUEST` / `LID_STATUS` |
| M3 | `RotaryTablePlantCD` | 12003 | bottle-correlated station events |
| M3 | `LidLoaderPlantCD` | 12006 | actuator and sensor abstraction |
| M3 IP | `FaultSupervisorCD` | 13003 | V2.1 transfer fault event/ACK/result |
| M2 | `M2TransferFaultAdapterCD` | 13002 | V2.1 recovery request |

Status values are `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`, and `4 FAULT`.
Status requests are observational and cannot start or acknowledge operations.

## GP behaviour

- The rotary Plant stores six independent bottle slots: P1 load, P2 fill, P3
  lid, P4 cap, P5 transfer and P6 label/unload.
- A step is committed atomically only after a matching `ROTATION_DONE(cycleId)`
  and alignment confirmation. P6 must be physically clear first.
- Every completion carries `bottleId`; stale, mismatched and duplicate events
  are rejected.
- M4 full-context handoffs use
  `bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId`.
- `BOTTLE_AT_FILL` and `BOTTLE_AT_LABEL` are reliable event offers rather than
  level commands. M3 retains the pending bottle context and sends at most three
  identical transport copies. Each copy is PRESENT for 500 ms and copies are
  separated by a 100 ms ABSENT gap. A matching `MARK_FILLED(bottleId)` or
  `MARK_LABELLED(bottleId)` cancels the remaining copies; receivers must reject
  duplicate bottle IDs. Signal names, payloads and ports remain unchanged.
- The lid loader retains the active bottle identity and de-energises both
  actuators on timeout. Fault reset requires cause-specific evidence.
- Alignment timeout has no automatic `REHOME`: M1 safe-stop, bottle-position
  reconciliation and independent position evidence are required.

## IP fault supervisor

`FaultSupervisorCD` implements the report's rotary, lid and transfer fault
catalogue. It validates V2.1 event identity, source epoch and state version,
selects a fault-specific policy, enforces one-attempt budgets, correlates
request/ACK/result messages, verifies independent evidence and publishes the
result to M1. It never sends an actuator command.

`ARRIVAL_TIMEOUT` and `PICK_TIMEOUT` permit one guarded attempt. Lid depletion
uses `RESOURCE_WAIT` without consuming an attempt. Alignment uncertainty,
stall, failed sensors, ambiguous lid placement/departure and position conflict
request coordinated safe stop and then remain locked until manual evidence and
a newer Controller result both pass validation. Manual evidence alone cannot
clear a fault, and M1 alone owns the final `RESUME`/`HOLD` decision.

The M1 signal names are frozen. The IP implementation validates the following
test/integration payload profile until M1 activates its physical safe-stop
source: `V2|eventId|sourceEpoch|SAFE_STOPPED|stateVersion` and
`V2|eventId|sourceEpoch|RESUME_or_HOLD|reason|stateVersion`. Production M1
currently records requests and retains HOLD rather than inventing physical
safe-stop evidence.

`FaultManagementGUI` displays the selected policy, attempt count, validated
evidence, event trace, metrics and GP Controller status. Its controls follow
testable enablement rules. Fault injection and simulated peer evidence are
available only with `-Dm3.testMode=true`; they pass through the same validation
model and never drive an actuator.

## Build and verify

From the repository root, first verify the project-pinned Java and SystemJ
toolchain described in `../../toolchain/README.md`. Then use the verified
SystemJ library directory below:

```sh
mkdir -p build/member3-generated build/member3-classes

java -cp "/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  com.systemj.compiler.JavaPrettyPrinter \
  -d build/member3-generated --nojavac --silence \
  machines/rotary_lid/rotary_table_controller.sysj \
  machines/rotary_lid/rotary_table_plant.sysj \
  machines/rotary_lid/lid_loader_controller.sysj \
  machines/rotary_lid/lid_loader_plant.sysj \
  machines/rotary_lid/fault_supervisor.sysj \
  machines/rotary_lid/fault_tolerance_demo_driver.sysj \
  machines/rotary_lid/member3_demo_driver.sysj

javac -cp "/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  -d build/member3-classes \
  build/member3-generated/*.java machines/rotary_lid/*.java

java -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  Member3ControllerSelfTest
java -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  Member3PlantSelfTest
java -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  FaultSupervisorSelfTest
java -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  FaultToleranceEvaluation
```

The evaluation prints all eleven report scenarios, policy coverage, verified
automatic recovery and unsafe-output count. Expected output is `PASSED` from
all four tests. Run the canonical integrated
M3 runtime after neighbouring receiver ports are available:

```sh
java -Djava.awt.headless=false \
  -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  com.systemj.SystemJRunner machines/rotary_lid/member3_system.xml
```

Use `-Djava.awt.headless=true` for terminal-only integration testing.

For a self-contained one-bottle demonstration, use the same compiled classes:

```sh
java -Djava.awt.headless=true \
  -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  com.systemj.SystemJRunner machines/rotary_lid/member3_demo.xml
```

The demo simulates the M2/M4 hand-offs and must print
`MEMBER3 SYSTEMJ DEMO PASSED` after P1-P6 processing and verified removal.

Run the self-contained IP protocol demonstration with:

```sh
java -Djava.awt.headless=true \
  -cp "build/member3-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  com.systemj.SystemJRunner machines/rotary_lid/fault_tolerance_demo.xml
```

It uses the real M2 Adapter and M3 `FaultSupervisorCD` and must print
`FAULT_TOLERANCE_SYSTEMJ_DEMO PASSED` after the complete
event/request/ACK/result/M1-resume exchange.
