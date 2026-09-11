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
- The lid loader retains the active bottle identity and de-energises both
  actuators on timeout. Fault reset requires cause-specific evidence.
- Alignment timeout has no automatic `REHOME`: M1 safe-stop, bottle-position
  reconciliation and independent position evidence are required.

## IP fault supervisor

`FaultSupervisorCD` parses the frozen M2/M3 V2.1 payloads, rejects stale or
invalid state versions, correlates ACK/result evidence and keeps an event
history. Only `ARRIVAL_TIMEOUT` can receive one `RETRY_TRANSFER` after
independent safe-stop evidence. Departure ambiguity, motor stall, sensor
failure and position conflict require manual reconciliation. M1 alone owns the
global hold/resume decision.

The M1 FT signal names are frozen, but their payload field order is not yet
defined. The runtime therefore does not infer safety from signal presence and
does not emit invented M1 payloads. Freeze that schema before end-to-end FT
integration.

`FaultManagementGUI` displays supervisor decisions, event history and GP
controller status. Fault injection is available only with
`-Dm3.testMode=true` and enters the supervisor as test data; it never drives an
actuator.

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
```

Expected output is `PASSED` from all three tests. Run the canonical integrated
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
