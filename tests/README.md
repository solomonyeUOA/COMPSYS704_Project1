# POS + Coordinator + Mock + Visualisation Integration Test

`MockController` is **TEST ONLY**. It combines eight Coordinator-facing
Machine Controller interfaces in one Clock Domain on port 11002. This does not
replace the production Clock Domains or ports.

## Test path

```text
POSCD -> ORDER -> CoordinatorCD
CoordinatorCD -> START_ORDER / ratios / eight STATUS_REQUEST signals
MockControllerCD -> eight STATUS signals
Mock final path -> Capper -> Conveyor output -> Bottle Unloader
Bottle Unloader -> BOTTLE_DONE -> CoordinatorCD
CoordinatorCD -> ORDER_COMPLETE -> POSCD
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

Use Java 8 and replace `<SYSTEMJ_LIB_DIR>` with the course SystemJ JAR folder.
Run from the repository root.

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
  xuqi_pos/POSVisualisation.java visualisation/ABSVisualisation.java
```

Do not manually edit generated Java files.

Run the framework-free protocol/state check:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" OrderV1SelfTest
```

Expected output: `OrderV1SelfTest PASSED`. This check now also verifies that an
alert alone does not stop production, a safe-stop request cannot produce an
ACK without independent evidence, recovery-ready does not auto-resume, failed
recovery retains HOLD, new orders are rejected while HOLD is active, one
completion transport window does not consume multiple attempts, and a late
copy of a completed order ID cannot restart that order. It also verifies that
a held `BOTTLE_DONE` window counts once and re-arms only after an `ABSENT`
reaction.

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
java "-Dabs.pos.testOrder=PO001|1|P1,60,40,2" `
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
POS sent ORDER: PO001|1|P1,60,40,2
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

## Three-runtime regression

When Visualisation is intentionally omitted, use
`tests/coordinator_mock_no_visualisation.xml`. This preserves the
POS/Coordinator/Mock regression without changing SystemJ signal declarations.
