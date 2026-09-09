# POS + Coordinator + Mock + Visualisation Integration Test

`MockController` is **TEST ONLY**. It combines eight Coordinator-facing
Machine Controller interfaces in one Clock Domain on port 11002. This does not
replace the production Clock Domains or ports.

## Test path

```text
POSCD -> ORDER -> CoordinatorCD
POSCD -> SYSTEM_RESET_REQUEST(resetId) -> CoordinatorCD
CoordinatorCD -> START_ORDER / ratios / eight STATUS_REQUEST signals
MockControllerCD -> eight STATUS signals
Mock final path -> Capper -> Conveyor output -> Bottle Unloader
Bottle Unloader -> BOTTLE_DONE -> CoordinatorCD
CoordinatorCD -> ORDER_COMPLETE -> POSCD
CoordinatorCD -> M2/M3/M4/VIZ system-reset fan-out
MockControllerCD -> M2/M3/M4 matching reset ACKs -> CoordinatorCD
CoordinatorCD -> SYSTEM_RESET_COMPLETE -> POSCD (only after all three ACKs)
CoordinatorCD -> ten VIZ_* signals -> ABSVisualisationPlantCD
M3 FaultSupervisorCD -> four FT_* safety inputs -> CoordinatorCD:11001
CoordinatorCD -> FT_SAFE_STOP_ACK / FT_RESUME_DECISION ->
  FaultSupervisorCD:13003 (declared/mapped; not emitted without evidence/schema)
```

The optional Fault-Tolerance peer may be absent for the nominal GP test. The
Coordinator records `FT_FAULT_ALERT`, enters an M1 order/batch-dispatch HOLD on
`FT_SAFE_STOP_REQUEST`, retains HOLD on `FT_RECOVERY_READY` or
`FT_RECOVERY_FAILED`, and never treats status polling as safe-stop evidence.
The two optional FT outputs use `OptionalSimpleClient`, so an absent
`FaultSupervisorCD` does not cause connection attempts before a real FT value
is emitted.

The production XML (not the unified Mock mapping) sends Conveyor polling to
`ConveyorControllerCD:11009` and Rotary polling to
`RotaryTableControllerCD:11003`.

## Compile

Use the project-pinned Temurin OpenJDK 8u502 and SystemJ JAR directory. Verify
both before compiling, then replace `<SYSTEMJ_LIB_DIR>` below with the verified
directory. Run from the repository root.

```powershell
python tools/verify_project_toolchain.py `
  --java-home "<TEMURIN_8U502_HOME>" `
  --systemj-lib "<SYSTEMJ_LIB_DIR>"
```

Do not continue unless the command prints `PROJECT_TOOLCHAIN_OK`.

```powershell
New-Item -ItemType Directory -Force build/generated,build/classes

java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence xuqi_pos/pos.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence xuqi_coordinator/coordinator.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence tests/mock_controller.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence visualisation/abs_visualisation_plant.sysj

$generatedSources = Get-ChildItem build/generated -Filter *.java |
  Select-Object -ExpandProperty FullName
$commonSources = Get-ChildItem common -Filter *.java |
  Select-Object -ExpandProperty FullName
$testSources = Get-ChildItem tests -Filter *.java |
  Select-Object -ExpandProperty FullName

javac -cp "<SYSTEMJ_LIB_DIR>/*" -d build/classes `
  @generatedSources @commonSources @testSources `
  xuqi_pos/POSVisualisation.java `
  visualisation/ABSVisualisation.java `
  visualisation/ABSVisualisationFlowModel.java `
  visualisation/ABSVisualisationTeamIpModel.java
```

Do not manually edit generated Java files.

Run the framework-free protocol/state check:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" OrderV1SelfTest
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" OrderV2SelfTest
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" M1M4BatchSyncSelfTest
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" SystemResetSelfTest
```

Expected output: `OrderV1SelfTest PASSED`. This check now also verifies that an
alert alone does not stop production, a safe-stop request cannot produce an
ACK without independent evidence, recovery-ready does not auto-resume, failed
recovery retains HOLD, new orders are rejected while HOLD is active, one
completion transport window does not consume multiple attempts, and a late
copy of a completed order ID cannot restart that order. It also verifies that
a held `BOTTLE_DONE` window counts once and re-arms only after an `ABSENT`
reaction.

`M1M4BatchSyncSelfTest` verifies the simulation-only batch contract: retries
retain an identical `<orderId>-Pnn|quantity|sizeCode` payload, a product transition
creates the next deterministic batch ID, conflicting quantities do not mutate
the current identity, conflicting sizes are rejected, and the generated Coordinator exposes the expected
`M4_SIM_BATCH_REQUEST` value. It also verifies that an order ID `OrderV1`
accepts but the simulation transport cannot represent only skips the trigger:
the order is still accepted and no batch identity is retained.

## Run the four runtimes

Start receivers before the Coordinator:

1. Mock Controller
2. ABS Visualisation Plant
3. POS
4. Coordinator

```powershell
# Terminal 1
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/mock_controller.xml

# Terminal 2
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner visualisation/abs_visualisation_plant.xml

# Terminal 3 - automatic quantity=2 order
java "-Dabs.pos.testOrder=PO001|1|P1,S,60,40,2" `
  -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner xuqi_pos/pos.xml

# Terminal 4
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/coordinator_mock.xml
```

For console-only testing add `-Djava.awt.headless=true` to POS and
Visualisation. Normal interactive POS use omits `abs.pos.testOrder`.
For slower startup environments, add
`-Dabs.pos.testOrderDelayMillis=10000`; the normal test default remains five
seconds.
An integration-only automatic reset can be scheduled with
`-Dabs.pos.testResetDelayMillis=<milliseconds>`; normal POS runs omit this
property and can request reset only through the confirmed UI action.

For the mandatory consecutive-order regression, add
`-Dabs.pos.testOrderCount=2 -Dabs.pos.testOrderIntervalMillis=750`. POS assigns
the second order a new sequential ID after the first completion is accepted.
The default `ORDER` and `ORDER_COMPLETE` transport-copy `PRESENT` windows are
500 ms. Test-only overrides are available as
`-Dabs.pos.orderSignalHoldMillis=...` and
`-Dabs.coordinator.completionSignalHoldMillis=...`; these windows improve
cross-process observability without making either signal permanently present.
The test Mock similarly uses a 500 ms default window for each logical
`BOTTLE_DONE`, configurable with
`-Dabs.mock.bottleDoneSignalHoldMillis=...`, and inserts an `ABSENT` gap before
the next bottle event. The Coordinator edge-latches that window and counts it
only once.

## Expected evidence

POS:

```text
POS sent ORDER: PO001|1|P1,S,60,40,2
POS received completion: orderId=PO001, status=COMPLETED, ...
```

Mock final stage:

```text
[MOCK-LIFECYCLE] ... CAPPER ...->BUSY
[MOCK-LIFECYCLE] ... UNLOADER ...->BUSY
Mock Bottle Unloader emitted BOTTLE_DONE 1/2
...
Mock Bottle Unloader emitted BOTTLE_DONE 2/2
```

Coordinator:

```text
Coordinator BOTTLE_DONE 1/2 ...
Coordinator BOTTLE_DONE 2/2 ...
[COORD-LIFECYCLE] ORDER_COMPLETE attempt=1 PO001|COMPLETED|...
```

ABS Visualisation shows these eight machines:

```text
Bottle Loader / Conveyor / Rotary Turntable / Filler A / Filler B /
Lid Loader / Capper / Bottle Unloader

READY -> BUSY -> DONE
Progress=0/2 -> 1/2 -> order/batch completion
```

The display is an asynchronous read-only observer. When the final
`BOTTLE_DONE` and next-product dispatch occur in adjacent reactions, it may
coalesce a transient final count while the Coordinator log remains the
authoritative count/completion evidence.

The Coordinator may transmit the identical completion payload up to three
times for course-runtime connection timing tolerance. POS likewise may transmit
one validated `ORDER` as up to three transport copies. Each copy is held
`PRESENT` for a bounded wall-clock window and separated by an `ABSENT` gap.
These are still one logical order and one logical completion: Coordinator and
POS de-duplicate them, and stale completion copies from the first order cannot
interfere with the second active order.

This test validates POS/Coordinator, Coordinator/Mock and
Coordinator/Visualisation communication. It does not validate real Machine
Controllers, physical Plants, M3 `FaultSupervisorCD`, independent safe-stop
evidence, or the still-undefined M1 FT String payload field order.

## M1 POS and Coordinator extension coverage

### POS coverage

New POS submissions use ORDER V2:
`orderId|productCount|productId,sizeCode,A%,B%,quantity;...`, where `S` is
200 mL and `L` is 500 mL. `OrderV1` remains accepted by the Coordinator and
defaults to the S/200 mL profile. The simulation-only M4 payload is now
`batchId|quantity|sizeCode`. The Reset System button is the POS user entry
point; POS does not reset downstream components directly.

### Coordinator coverage

Coordinator retains the selected size/capacity as part of the active order,
publishes the size-aware M4 simulation batch, and owns the complete reset
fan-out/ACK barrier. These are responsibilities of the existing Coordinator,
not separate size or reset subsystems.

### Shared protocol and regression coverage

`SystemResetSelfTest` covers reset while idle/active, duplicate reset copies,
stale completion isolation, pending ORDER/completion/M4 retry cancellation,
post-reset order reuse safety, both bottle sizes, and an S-then-L order.
The unified Mock acknowledges the M2/M3/M4 reset requests independently.

Production teammate responsibilities remain intentionally unimplemented here:

- M2: receive `M2_SYSTEM_RESET` at `M2TransferFaultAdapterCD:13002`, safely
  clear/de-energise M2 state, then return the same ID as
  `M2_SYSTEM_RESET_ACK` to `CoordinatorCD:11001`.
- M3: receive `M3_SYSTEM_RESET` at `FaultSupervisorCD:13003`, safely reset
  M3 controller/fault state, then return matching `M3_SYSTEM_RESET_ACK`.
- M4: receive `M4_SYSTEM_RESET` at `BottleContextRegistryCD:11011`, safely
  clear contexts/queues/commands/model faults, then return matching
  `M4_SYSTEM_RESET_ACK`; also parse the new M4 simulation size field.

Each receiver must handle duplicate reset IDs idempotently and must ACK only
after reaching its defined safe initial state. Until all three production
receivers exist, Coordinator correctly remains `RESET_PENDING_EXTERNAL_ACK`.

## Three-runtime regression

When Visualisation is intentionally omitted, use
`tests/coordinator_mock_no_visualisation.xml`. This preserves the
POS/Coordinator/Mock regression without changing SystemJ signal declarations.
