# COMPSYS 704 Project 1: Automated Bottling System

A SystemJ simulation of a bottling production line with a Java Swing operator
interface, digital twins, fault tolerance and two bottle sizes. Controllers
decide actions; Plants simulate actuators and sensors. Visualisation is read-only.

## 1. Architecture and Ownership

Production flow:

```text
Bottle Loader -> Input Conveyor -> Rotary Table
  -> Filler A -> Filler B -> Lid Loader -> Capper
  -> Labeller -> Bottle Unloader -> Sort / Pack
```

The rotary table has six positions: P1 loading, P2 filling, P3 lid placement,
P4 capping, P5 transfer and P6 labelling/unloading. Rotation requires station
completion and clearance; a matching cycle is committed only once.

| Member | Group Project (GP) | Individual Project (IP) | Source |
| --- | --- | --- | --- |
| M1 | POS orders and Coordinator | Read-only hierarchical visualisation | [POS](xuqi_pos/), [Coordinator](xuqi_coordinator/), [Visualisation](visualisation/) |
| M2 | Loader, Conveyor, Labeller and Unloader | Workpiece and Resource digital twins | [Transfer](machines/transfer/) |
| M3 | Rotary Table and Lid Loader | Fault Supervisor, Watchdog and automatic failover | [Rotary/Lid](machines/rotary_lid/) |
| M4 | Filler A/B, Capper and Sort/Pack | S/L recognition, capacity and geometry profiles | [Filling/Capping](machines/filling_capping/) |

The launcher starts six JVMs: M2, M3, M4, Visualisation, POS and Coordinator.
They communicate through SystemJ signals using local TCP. Production mappings
and receiver ownership are listed in the
[integration manifest](integration/system-manifest.json).
The integrated simulation uses M4's `member4_simulation.xml`; its recognition
simulator is not a physical sensor. Mock/demo drivers are test-only.

## 2. Build and Run

Requirements:

- Python 3 (standard library only).
- Eclipse Temurin Java and javac `1.8.0_502` (Temurin 8.0.502+7).
- The 14 SystemJ JARs matching
  [the checksum lock](toolchain/systemj-project.sha256), supplied separately.

Run commands from the repository root. Set paths for your own computer first.

**Windows PowerShell**

```powershell
$env:PROJECT_JAVA_HOME = 'C:\path\to\jdk8'
$env:SYSTEMJ_LIB = 'C:\path\to\SystemJ\lib'
python tools/project.py test
python tools/project.py run --no-build
```

**macOS / Linux**

```sh
export PROJECT_JAVA_HOME="/path/to/jdk8/Contents/Home"
export SYSTEMJ_LIB="/path/to/SystemJ/lib"
python3 tools/project.py test
python3 tools/project.py run --no-build
```

On Linux, use the JDK root for `PROJECT_JAVA_HOME` (without `Contents/Home`).
The launcher also accepts `--java-home` and `--systemj-lib`. It verifies the
toolchain, compiles SystemJ and Java, then runs tests. After source updates,
rebuild before using `--no-build`; `python3 tools/project.py build` builds only.

For a slower GUI demonstration:

```sh
python3 tools/project.py run --demo-slowdown 5
```

Slowdown accepts 1-10 and scales simulated physical actions and their matching
timeouts, not heartbeats or telemetry. Windows also provides
[run-project.bat](run-project.bat) and [run-demo.bat](run-demo.bat).

Stop all six processes with **Exit Program** in POS or **Ctrl+C** in the
launcher terminal. Closing a single window does not stop the whole system.
Default ports are the XML ports plus 10000. If occupied, stop the old run or
use `--port-offset 20000`; do not edit source XML for local port changes.
Logs and generated runtime XML are in `build/runs/<timestamp>/`.

## 3. Demonstration

1. In POS, add products, select S/200 mL or L/500 mL, set quantity and the
   liquid A/B recipe, then submit. Percentages support one decimal place and
   must total 100.0%.
2. Observe the production flow, controller status and current batch in ABS.
   Open M2/M4 details to inspect bottle identities, stages and size profiles.
3. After order completion, submit another order without resetting.
4. In M3 Fault-Tolerance Monitor, enable **TEST MODE** and keep **Watchdog ON**.
   Select a scenario below and click **Arm fault for next order**. Device faults
   wait for the matching current/next action; a pending injection can be cancelled.
5. Check the failover notification, primary/backup state and logs. Verify that
   production continues after successful recovery, rather than treating a
   change of the active channel alone as success.

**Completion counts differ:** GP `BOTTLE_DONE` confirms verified unloading;
the digital twin reaches `COMPLETE` after confirmed sorting. Upstream
`OBSERVED_*` resource rows describe the last confirmed operation, not live
actuator measurements. M4 publishes additional read-only filler/capper stage
telemetry. Stale M3 telemetry is shown as UNKNOWN/STALE, not healthy.

## 4. M3 Fault Tolerance

| GUI injection | Expected response |
| --- | --- |
| `ROTARY_CONTROLLER_FAILURE` | Restore the rotary controller checkpoint into standby B; retain the original cycle |
| `LID_CONTROLLER_FAILURE` | Restore the lid controller checkpoint into standby B; retain the original bottle/action |
| `ROTARY_DRIVE_FAILURE` | Stop/hold, isolate failed drive A, connect B and finish the remaining rotation |
| `PICK_DRIVE_FAILURE` | Transfer the pick drive to B while preserving action/load state |
| `PLACE_DRIVE_FAILURE` | Transfer the place drive to B while preserving action/load state |
| `ROTARY_FEEDBACK_FAILURE` | Select healthy feedback B; still require actual simulated alignment |
| `PICK_TIMEOUT` | At most one retry for the same bottle, only with validated safe Plant evidence |
| `ROTARY_BACKUP_FAILURE` | Both rotary drives fail: expected SAFE / ERROR stop, not continued production |

- Watchdog ON monitors abnormalities and permits eligible automatic takeovers.
  OFF disables Watchdog monitoring/takeover, but does not disable controller
  interlocks or every Supervisor policy. GUI injection is rejected while
  automatic recovery is disabled or a recovery/safe hold is active.
- Drive transfer requires stop/hold, isolation and engagement feedback.
  Verification uses completion of the original physical action. Unknown
  position/load, failed interlocks or exhausted backups stop output.
- Controller takeover preserves state in the same JVM; only the active
  controller can issue output. It is not protection against a JVM crash.
- PICK_TIMEOUT retry requires the matching bottle, actuator home, no held lid,
  available material and usable drives. Completion evidence and the existing
  M1 automatic Resume decision are required before releasing the result.
- Successful takeover remains `DEGRADED`: B works, A remains failed.
  Repeating that GUI primary-failure test is rejected to avoid destroying B.
  Restart the simulation to repeat it with fresh simulated hardware.
- Recovery has no operator record/submit/approve steps. Unsupported or
  ambiguous faults remain held; no automatic repair, unlimited reset or
  automatic switchback is provided. Legacy fault routes remain for tests,
  including bounded transfer retry and resource-wait cases.
- Logs identify the module, action, timestamp, switch/attempt count and result.
  `FAULT_DETECTED -> SWITCH_TO_B -> FAILOVER_VERIFIED` distinguishes detection,
  selection and verified completion.

Key implementation:
[Supervisor](machines/rotary_lid/FaultSupervisorModelV2_1.java),
[Watchdog](machines/rotary_lid/SystemWatchdogV1.java),
[Controller standby](machines/rotary_lid/ControllerStandbyV1.java),
[Drive transfer](machines/rotary_lid/RedundantDriveV1.java),
[Pick retry](machines/rotary_lid/M3PickRecoveryV1.java).

## 5. Interfaces and State Consistency

- Status values: `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`, `4 FAULT`.
  Status polling never starts or completes work.
- Bottle handoffs retain identity and profile:
  `bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId`.
  Supported profiles are `S|200|GEOM_S|PACK_S` and `L|500|GEOM_L|PACK_L`.
- New POS orders use
  `orderId|productCount|productId,sizeCode,A%,B%,quantity;...`.
  V1 orders without size remain supported and default to S.
- M1 publishes `M4_BATCH_START=batchId|quantity|sizeCode`. M4 recognises the
  batch, fills A then B, applies the matching geometry profile and separates
  S/L packages. Internal recipe signals use tenths of a percent: `333 = 33.3%`.
- Completion and recovery messages use bottle/cycle/event identities, bounded
  transport offers and duplicate/stale-event checks. Retransmission must not
  repeat a physical action.
- **Reset System** is separate from fault recovery. M1 fans out a reset ID and
  waits for matching safe-state ACKs from M2/M3/M4 before reporting completion.
  Reset retires in-flight work; it is not an order-resume operation and does
  not repair failed redundant devices.

## 6. Verification

```sh
python3 tools/project.py test
python3 tools/validate_integration.py
```

The test runner discovers Java `*SelfTest` suites, runs the fault evaluation
matrix and validates SystemJ mappings. Coverage includes order validation,
S/L processing, identity-preserving handoffs, resets, twins, failover
interlocks, bounded retry and concurrent injection/monitoring. The command's
PASS/FAIL output is the test result; screenshots alone are not proof.

A bounded, headless mixed-size integration check:

```sh
python3 tools/project.py run --no-build --headless --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,2' --duration 120 --expect-completions 1 --expect-workpieces 3
```

Expected: one completed order, exactly three completed bottle twins, no rejected
updates and empty runtime error logs. Inspect each runtime's `*.out.log` and
`*.err.log` in the printed run directory on failure.
