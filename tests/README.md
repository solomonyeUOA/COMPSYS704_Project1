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
```

## Compile

Use Java 8 and replace `<SYSTEMJ_LIB_DIR>` with the course SystemJ JAR folder.
Run from the repository root.

```powershell
New-Item -ItemType Directory -Force build/generated,build/classes

java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence pos/pos.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence coordinator/coordinator.sysj
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
  pos/POSVisualisation.java visualisation/ABSVisualisation.java
```

Do not manually edit generated Java files.

Run the framework-free protocol/state check:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" OrderV1SelfTest
```

Expected output: `OrderV1SelfTest PASSED`.

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
  com.systemj.SystemJRunner pos/pos.xml

# Terminal 4
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/coordinator_mock.xml
```

For console-only testing add `-Djava.awt.headless=true` to POS and
Visualisation. Normal interactive POS use omits `abs.pos.testOrder`.
For slower startup environments, add
`-Dabs.pos.testOrderDelayMillis=10000`; the normal test default remains five
seconds.

## Expected evidence

POS:

```text
POS sent ORDER: PO001|1|P1,60,40,2
POS received completion: orderId=PO001, status=COMPLETED, ...
```

Mock final stage:

```text
Mock final path: Capper DONE
Mock final path: Conveyor output DONE
Mock Bottle Unloader emitted BOTTLE_DONE 1/2
...
Mock Bottle Unloader emitted BOTTLE_DONE 2/2
```

Coordinator:

```text
Coordinator BOTTLE_DONE 1/2 ...
Coordinator BOTTLE_DONE 2/2 ...
Coordinator sent ORDER_COMPLETE: PO001|COMPLETED|...
```

ABS Visualisation shows these eight machines:

```text
Bottle Loader / Conveyor / Rotary Turntable / Filler A / Filler B /
Lid Loader / Capper / Bottle Unloader

READY -> BUSY -> DONE
Progress=0/2 -> 1/2 -> 2/2
```

The Coordinator may transmit the identical completion payload up to three
times for course-runtime connection timing tolerance. This is one logical
completion and POS displays it once.

This test validates POS/Coordinator, Coordinator/Mock and
Coordinator/Visualisation communication. It does not validate real Machine
Controllers or physical Plants.

## Three-runtime regression

When Visualisation is intentionally omitted, use
`tests/coordinator_mock_no_visualisation.xml`. This preserves the
POS/Coordinator/Mock regression without changing SystemJ signal declarations.
