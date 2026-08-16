# POS + Coordinator + Mock + Visualisation Integration Test

`MockController` is **TEST ONLY** and is not a real Machine Controller or
physical Plant. `ABSVisualisationPlantCD` is also display-only; it does not
control machines.

## Test path

```text
POSCD
  -> ORDER
CoordinatorCD
  -> START_ORDER / FILL_A_RATIO / FILL_B_RATIO / STATUS_REQUEST
MockControllerCD
  -> STATUS / BOTTLE_DONE
CoordinatorCD
  -> ORDER_COMPLETE -> POSCD
  -> VIZ_STATUS / VIZ_PROGRESS -> ABSVisualisationPlantCD
```

## Compile

Use Java 8 and replace `<SYSTEMJ_LIB_DIR>` with the directory containing the
course SystemJ JARs. Run from the repository root.

```powershell
New-Item -ItemType Directory -Force build/generated,build/classes

java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence pos/pos.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence coordinator/coordinator.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence tests/mock_controller.sysj
java -cp "<SYSTEMJ_LIB_DIR>/*" com.systemj.compiler.JavaPrettyPrinter `
  -d build/generated --nojavac --silence `
  visualisation/abs_visualisation_plant.sysj

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

Do not copy or manually edit generated Java files.

Run the framework-free protocol/state check:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" OrderV1SelfTest
```

Expected output is `OrderV1SelfTest PASSED`.

## Run the four runtimes

Start receivers before the Coordinator. This order was selected from actual
course-runtime testing and avoids `SimpleClient` connection timing loss:

1. Mock Controller
2. ABS Visualisation Plant
3. POS
4. Coordinator

Terminal 1:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/mock_controller.xml
```

Terminal 2:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner visualisation/abs_visualisation_plant.xml
```

Terminal 3, automatic reproducible test order:

```powershell
java "-Dabs.pos.testOrder=PO001|1|P1,60,40,2" `
  -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner pos/pos.xml
```

For normal interactive use, remove the `-Dabs.pos.testOrder=...` argument. The
Swing POS form then sends only when the user selects **Submit Order**.

Terminal 4:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/coordinator_mock.xml
```

The automatic test order waits five seconds before it becomes available to
POSCD, allowing Terminal 4 to start.

## Headless test option

For CI or console-only testing, add this JVM option to the POS and
Visualisation commands:

```text
-Djava.awt.headless=true
```

The same SystemJ signals run, and console messages provide test evidence.

## Expected results

POS:

```text
POS sent ORDER: PO001|1|P1,60,40,2
POS received completion: orderId=PO001, status=COMPLETED, ...
```

Coordinator:

```text
Coordinator accepted ORDER PO001, ...
Coordinator BOTTLE_DONE 1/2 ...
Coordinator BOTTLE_DONE 2/2 ...
Coordinator sent ORDER_COMPLETE: PO001|COMPLETED|...
```

ABS Visualisation:

```text
Bottle Loader / Transport / Filler A / Filler B / Lid Loader / Capper
READY -> BUSY -> DONE

Progress=0/2
Progress=1/2
Progress=2/2
```

The Coordinator may transmit the identical completion payload up to three
times because the supplied course `SimpleClient` can lose an isolated
cross-runtime signal during connection timing. This is one logical completion;
the POS de-duplicates it and displays the result once.

This test validates POS/Coordinator, Coordinator/Mock and
Coordinator/Visualisation communication only. It does not validate real
Machine Controllers or physical Plants.

## Original three-runtime regression

If the Visualisation runtime is intentionally omitted, use
`tests/coordinator_mock_no_visualisation.xml` for the Coordinator. The supplied
course `SimpleClient` repeatedly attempts to connect every configured output,
so using the normal Visualisation mapping without a receiver can delay the
Coordinator.

Start Mock, POS and then Coordinator as above, but use:

```powershell
java -cp "build/classes;<SYSTEMJ_LIB_DIR>/*" `
  com.systemj.SystemJRunner tests/coordinator_mock_no_visualisation.xml
```

This preserves the original POS/Coordinator/Mock regression path without
changing any SystemJ signal declaration.
