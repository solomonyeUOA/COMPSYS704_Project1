# Member 3: Rotary Table, Lid Loader and Fault Supervisor

This folder contains the M3 SystemJ Controllers, simulated Plant models,
fault-tolerance IP extension, visualisation and deterministic tests. The
implementation follows `COMPSYS704_Interface_Interim_Final.xlsx` V2.1.

## Frozen boundaries

### Unattended failover (current behavior)

- `ROTARY_CONTROLLER_FAILURE` and `LID_CONTROLLER_FAILURE` inject an in-process
  fail-stop controller failure at the next controller reaction. A synchronized,
  independent checkpoint is restored into the standby controller; the old object
  is no longer an output source. The synchronized M3 facade is the sole output
  gate. Cycle identity, bottle identity, timers and completion-offer state survive.
- This is component/checkpoint redundancy, not redundant PLC hardware or separate
  JVMs. It does not tolerate a JVM crash, a blocked SystemJ scheduler, corrupted
  shared memory, or a common software defect. Failure detection here is diagnosed
  fail-stop injection, not a claim of independent hardware heartbeat monitoring.
- Watchdog OFF prevents automatic takeover. Failed channels remain failed;
  exhausted or unsynchronized backups inhibit output. A completed original action
  confirms failover; selection alone does not report success. Normal system reset
  does not repair the failed controller pair; a new simulation session does.
- Drive failures retain the explicitly simulated isolatable-channel model.
  `MOTOR_STALL` is not treated as proof of a replaceable drive failure. Mechanical
  faults, unknown position and missing resources are not repaired by selecting B.
- Operator recovery buttons, artificial resource refill and GUI-supplied evidence
  have been removed. Legacy callable test APIs reject manual recovery and emit no
  signals. Existing automatically validated recovery protocols remain; faults
  without such a path stay held. Reset is a separate explicit simulation operation,
  not an automatic recovery step.
- M1 receives two additional read-only controller rows over the existing M3
  redundancy telemetry signal. M2/M4 interfaces are unchanged.

Run `ControllerStandbySelfTest` for 200 mid-action takeover cases plus output
fencing, exhausted/unsynchronized backup and Watchdog OFF checks. In the GUI,
inject the two controller-failure codes during production; expect A FAILED,
B VERIFYING, then DEGRADED after the original action completes. A second failure
must stop output rather than resurrect A. Notifications never require approval.

Older manual-recovery examples below describe the earlier protocol/test workflow;
they are not enabled recovery actions in the current unattended implementation.

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
  The Controller keeps DONE present while waiting for the Plant barrier to
  reopen. It must observe ROTATION_READY absent during the active cycle before
  accepting a subsequent ready indication. The Plant accepts duplicate DONE
  for its last committed cycle without shifting slots or clearing offer latches
  again; early and mismatched commits are rejected. This lets a later sampling
  reaction recover a missed commit before the P2 filling offer is produced.
- Every completion carries `bottleId`; stale, mismatched and duplicate events
  are rejected.
- M4 full-context handoffs use
  `bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId`.
- `BOTTLE_AT_FILL`, `BOTTLE_AT_CAP` and `BOTTLE_AT_LABEL` use bounded event offers rather than
  level commands. M3 retains the pending bottle context and sends at most three
  identical transport copies. Each copy is PRESENT for 500 ms and copies are
  separated by a 100 ms ABSENT gap. A matching `MARK_FILLED(bottleId)`, `MARK_CAPPED(bottleId)` or
  `MARK_LABELLED(bottleId)` cancels the remaining copies; receivers must reject
  duplicate bottle IDs. Signal names, payloads and ports remain unchanged.
  Exhausting the copies does not imply completion: the station barrier remains
  closed until a valid completion arrives. Bounded retransmission cannot
  guarantee delivery during a longer receiver outage.
- Runtime duration measurements use a monotonic clock so wall-clock corrections
  do not change motor timing, lid timing or notification windows.
- The lid loader retains the active bottle identity and de-energises both
  actuators on timeout. Uncertain faults stay held without a GUI reset path.
- The simulated lid magazine has a finite geometry-derived capacity rather
  than an order-derived test value. The model assumes a 120 mm internal
  height, less 8 mm top pick clearance, a 12 mm bottom follower, 4 mm sensor
  clearance and a 6 mm safety allowance. With a 3 mm stacked-lid thickness,
  the usable height is 90 mm and
  `floor(90 / 3) = 30` lids. `magazineCapacity` remains fixed at 30 while
  `magazineCount` starts at 30, decreases only after a completed placement,
  and is never allowed to exceed capacity during `REFILL_LIDS(Integer)`.
  At zero inventory the Plant removes `LID_AVAILABLE` and emits the existing
  `LID_MAGAZINE_EMPTY` signal; no new integration interface is required.
- Alignment timeout has no automatic REHOME; uncertain position stays held.

## IP fault supervisor

`FaultSupervisorCD` implements the report's rotary, lid and transfer fault
catalogue. It validates V2.1 event identity, source epoch and state version,
selects a fault-specific policy, enforces one-attempt budgets, correlates
request/ACK/result messages, verifies independent evidence and publishes the
result to M1. It never sends an actuator command.

Confirmed local drive failures use A/B failover without operator confirmation.
ARRIVAL_TIMEOUT uses the existing bounded transfer retry. PICK_TIMEOUT now has
an executable M3 adapter, described below. MAGAZINE_EMPTY stays in RESOURCE_WAIT:
no automatic replenishment hardware or production resource-restored caller is
connected. Other non-retry faults remain safely held; the old four operator
recovery controls are not enabled. Explicit simulation Reset is separate.

### Executable PICK_TIMEOUT retry

LidLoaderControllerCD calls M3PickRecoveryV1 after controller reactions. The
adapter samples actual local Plant evidence without holding the controller lock:
the original bottle must still be at the lid station, the actuator must already
be home, no lid may be held, material must be available and both required drive
groups must be usable. Watchdog must permit recovery. Missing conditions reject
the request; there is no invented home signal, automatic fault clearing or
automatic mechanical retraction.

An accepted request passes through the existing supervisor ACK/result protocol
and restarts only the pick stage of the SAME bottle. The controller retains the
original fault identity on a failed retry, preventing a new retry budget. Plant
placement count must increase exactly once and Controller must reach DONE before
RECOVERY_READY is published. Completion offers remain gated until the existing
M1 automatic interlock policy returns a correlated Resume. The supervisor is
then IDLE and the original bottle's completion can be published. No operator
confirmation and no M2/M4 changes are involved.

Persistent pick failure, unavailable drives, missing bottle, unsafe held load,
Watchdog OFF, or protocol timeouts keep the recovery stopped. In particular a
real timeout with the Plant still away from home is NOT automatically retried.
The GUI distinguishes in-progress retry, waiting for M1, and unavailable recovery.
M3PickRecoverySelfTest verifies the actual adapter/Plant/controller/M1 path and
negative cases, rather than just checking that a retry policy exists.

Watchdog OFF disables its own monitoring and automatic device selection, not
all controller fault detection or all Supervisor policies. ON permits local failover
only after fail-stop isolation and standby/position checks. A lost process
heartbeat does not identify a failed motor: no redundant process exists, so
the watchdog latches SAFE / ERROR instead of resetting the whole line.
System reset does not repair failed drives; a fresh runtime session creates
new simulated hardware.

### M1 read-only redundancy display

M3_REDUNDANCY_VIEW is a direct M3 Plant to M1 visualisation signal, independent
of M2/M4 twins and Coordinator business decisions. The versioned M3R1 snapshot
contains a runtime epoch, sequence, timestamp and four structured device rows.
M3VisualTelemetrySender uses one latest-only slot and a daemon network worker;
an absent viewer cannot block SystemJ execution or grow a pending queue.

M1 shows primary/standby health below the process schematic and outlines Rotary
and Lid Loader while operating on backup, verifying or locked out. It rejects
malformed/stale/repeated snapshots and marks telemetry older than five seconds
UNKNOWN/STALE. This projection does not change bottle state, controller status,
recovery policy or M2/M4 detail views. The XML endpoints use the existing M1
visualisation receiver port and follow the launcher's port-offset mapping.

### Rotary position feedback failover

The unified Test controls list also contains four explicit device-health tests:
ROTARY_DRIVE_FAILURE, PICK_DRIVE_FAILURE, PLACE_DRIVE_FAILURE and
ROTARY_FEEDBACK_FAILURE. They damage the active channel only when its matching
physical action is running (current or next action). The button never selects B
or emits completion. Normal runtime health checking performs failover with
Watchdog ON, records it and displays a non-modal notification. Pending tests can
be cancelled. A repeated fault targets the now-active channel, so two failures
exhaust a pair rather than repairing A or switching forever. Existing timeout,
resource and manual recovery policies are unchanged.

The staged POSITION_SENSOR_FAILURE test now fails the active simulated position
channel at the next physical rotation instead of directly locking the Controller.
RedundantPositionFeedbackV1 models two independently diagnosed fail-stop channels
observing the same Plant position. With Watchdog ON, an unavailable primary is
replaced by the healthy standby. The existing TABLE_ALIGNED_WITH_SENSOR signal
remains false until the actual simulated movement is aligned. The original cycle
ID and exact-once slot commit remain unchanged; polling never advances selection.
The Supervisor lists ROTARY POSITION FEEDBACK and emits non-modal notifications.

This does not diagnose an arbitrary contradictory sensor reading: unknown faults
reported directly by the Controller still require evidence-gated recovery. Misalignment is not
corrected by changing channels. A second injected position failure exhausts the
pair and stops production. System reset preserves failed channel health. No
automatic repair or switchback is provided. Existing locked runtime processes
must be restarted to load this implementation; it does not hot-resume old orders.
The two channels are simulated redundancy, not independent physical hardware.

The Supervisor displays all three drive groups, system degradation and
non-modal notifications for detection, switching, verification and safe stop.
AutomaticOnlyFtSelfTest checks automatic local selection and rejected premature approval,
OFF gating, dual failure and absence of global reset. RedundantDriveSelfTest
checks physical-model completion and exact-once effects. These are simulation
tests, not hardware safety certification.

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
# M3 Primary / Standby Drive Simulation

The rotary motor, lid pick axis and lid place axis each have independent A/B
drive health and selection. This is a simulated dual-drive mechanism, not a
claim that physical redundant motors or clutches exist. The assumed fault is
fail-stop with a disconnectable transmission and a load-holding stop. Isolation
is now a delayed Plant feedback, not an assumption supplied by fault injection.
Pick/place are modelled as separate axes sharing the original lid state machine.

In the M3 monitor, enable TEST MODE and open **M3 redundant drives**. Select
ROTARY, PICK or PLACE and channel A or B, then **Fail selected drive**. Unlike
the original staged fault injection, this immediately damages that drive and
the next/current matching action detects it. A healthy standby takes over only
if the failed active channel is isolated and the model's existing fault flags
do not make the transfer ambiguous. The current action retains completed travel;
unpowered intervals do not advance it. Completion feedback is produced only
after the remaining physical-model duration, not by the switch itself.

Observe active=B, A=FAILED/ISOLATED and switches=1, followed by FAILOVER_VERIFIED
in the existing Event log tab and process stdout ([M3-DRIVE]). The original
bottle identity, rotary cycle commit, downstream offers and lid inventory logic
remain authoritative. A/B failure stops motion; subsequent controller timeouts
use the original evidence workflow, but cannot repair either failed drive.
Drive SAFE / ERROR remains latched.

There is no drive repair or drive-switch approval control. Damage and lockout survive system reset.
A fresh runtime session creates new simulated hardware.

Drive failover is local Plant behaviour, separate from Watchdog reset/restart;
selection is enabled only while Watchdog is ON. Use ROTARY_DRIVE_FAILURE for
an isolatable drive failure; MOTOR_STALL remains a controller fault, not proof
that changing motors will clear a shared mechanical jam. Repeating drive failure without repair
can exhaust both motors and must stop production. Existing alignment,
sensor, pick/place timeout and material faults are NOT redefined as these new
isolated drive faults. Shared mechanical jams, sensor ambiguity and supply loss
are not repaired by selecting a standby. No M1/M2/M4 interfaces are changed.

`RedundantDriveSelfTest` exercises mid-step rotary failover with the real M3
controller model, pick/place physical completion, exact-once effects, exhausted
standby, isolation/interlock rejection and damage/reset persistence. These
are deterministic in-process tests, not physical-hardware certification.

The Supervisor also presents the three drive groups in its permanent summary
and component list. Active-drive failure detection, standby selection, verified
action completion and lockout produce sequenced, non-modal GUI notifications.
One notification window accumulates recent events; repeated GUI refreshes do not
replay them or require operator approval for a permitted local switch. A pending
injection is not reported as successful recovery. This detects the simulated
drive-health failure, not a diagnosis that every timeout implies motor failure.

## Feedback-gated mechanical transfer

`DriveMechanismV1` is a discrete Plant model with separate commands and feedback.
`RedundantDriveV1` requests STOPPING -> ISOLATING_PRIMARY -> CONNECTING_BACKUP.
Each phase requires actual simulated feedback and has a bounded deadline. Only
then is B selected. Original action completion is required before VERIFIED.
The model assumes an ideal load-holding stop, independently disconnectable
couplings, and constant powered motion. It does not simulate inertia, brake
torque, electrical energy, clutch wear, or certified safety hardware. In
particular, a failed stop feedback means position must not be trusted as safe;
the frozen animation is not proof of physical standstill.

Rotary stores angle, speed and the original absolute target (one 60-degree step).
Transfer intervals do not count as travel and bottle slots move only on the
existing matching cycle commit. Pick/place retains its existing action and lid
held state. A lost lid cannot be repaired by another motor. Inventory is still
the existing completed-placement accounting, not a new raw-material model.

Additional GUI injections use ROTARY_, PICK_ or PLACE_ with STOP_FAILURE,
HOLD_FAILURE, ISOLATION_FAILURE, ENGAGEMENT_FAILURE, BACKUP_FAILURE or
MECHANICAL_JAM. These scenarios inject primary failure plus the selected
obstruction (BACKUP_FAILURE exhausts both channels). ROTARY_FEEDBACK_DISAGREEMENT
and PLACE_LOAD_LOSS test ambiguous position and loss of the held load. They
are consumed only during the matching physical action and latch SAFE_ERROR;
there is no automatic repair or retry loop. Existing reset is not a hardware
repair. Use a fresh test runtime for independent destructive scenarios.

The M3 drive tab shows coupling, stopped/held feedback, angle/target/speed and lid
retention. M1 accepts the three transfer phases on its existing read-only
telemetry. No M2/M4 protocol or production implementation is changed.
`PhysicalFailoverSelfTest` verifies mid-motion progress, original targets,
exact-once commits, failed feedback, disagreement, load loss and Watchdog OFF.

### Shared M3 visual telemetry

The six M1 redundancy rows share one M3 snapshot stream; six STALE labels do
not diagnose six separate device disconnections. M3VisualTelemetrySender holds
the latest PRESENT value instead of immediately sending ABSENT, which could
overwrite a snapshot before the visualization clock-domain samples it. M1
accepts each increasing sequence only once and retains its five-second freshness
limit. Re-reading a held value cannot conceal a stopped publisher. Network
transmission remains off the SystemJ scheduler. M3TelemetryTransportSelfTest
exercises real loopback TCP with a consumer slower than the sender.

### Representative GUI injection scenarios

The Test controls list contains eight scenarios: rotary/lid controller takeover,
rotary/pick/place drive takeover, rotary feedback takeover, bounded PICK_TIMEOUT
retry, and ROTARY_BACKUP_FAILURE (both drives fail; expected safe stop).
The redundant-drives tab is read-only; the duplicate arbitrary A/B failure
buttons were removed. Low-level negative injection routes remain for self-tests.

GUI commands reject new injections during transfer/verification or safe hold.
Repeating a primary-failure demonstration on an already degraded pair is rejected:
the old implementation failed the *active* channel again, exhausting the backup.
A fresh runtime is needed to repeat that demonstration with healthy simulated
hardware. Reset does not repair failed devices. Tests intentionally exhausting
both drives must remain stopped; this is not successful recovery or a GUI hang.

FaultInjectionCatalogSelfTest covers cancellation, in-flight rejection, repeat
rejection without leaving an armed request, and testing another healthy pair.
These are command-level tests, not native GUI click tests.
