# COMPSYS704 Project 1 - M1-M4 Integration Draft

This branch proposes the complete M1/M2/M3/M4 simulation integration for the
team repository `COMPSYS704_Project1`, based on team main `42317b9` (PR #18).
It imports the reset/twin and finishing-stage fixes previously tested in
`COMPSYS704_Project1_1` at `4922537` and `a179761`. It is a draft for all four
members to review, not an assertion that the team has approved every change.

## Run locally (Windows)

In PowerShell:

```powershell
cd D:\Auckland_University\COMPSYS_704\Project1\github
python tools\project.py test
python tools\project.py run --no-build
```

The first command verifies Java 8 and the Lab3 SystemJ JARs, compiles the project,
and runs regression tests. The second launches all six real simulation runtimes,
including POS, the ABS visualization and M3 fault GUI. Enter products in POS and
submit. Stop **all** runtimes with Ctrl+C in the launching terminal. Alternatively,
double-click `run-project.bat` to build and open the interactive simulation.

Open the **M2 Digital Twin** card in ABS visualization, then **Live workpieces**
or **Live resources**. Workpieces show bottle ID, confirmed stage, resource,
version and S/200 mL or L/500 mL profile. Resource rows include machine ID/type,
linked bottle, status, operation, fault and version. M2 resources have live
controller observations; upstream `OBSERVED_*` rows are last confirmed completed
operations, not a continuous actuator-state feed. The existing overview animation
remains symbolic; neither twin table controls machines.

The launcher defaults to ports **+10000** (e.g. Coordinator 21001), because this
PC has an unrelated service on canonical port 11001. It remaps every XML in a
generated run directory without changing source XML. Use `--port-offset 20000`
for a second isolated run. Logs remain under `build/runs/<timestamp>`.

The default paths match M2's PC: Adoptium JDK `8.0.502.7` and its Lab3 `lib`
folder. On another computer, use your own paths to the same pinned Java 8 and
SystemJ JARs; the compiler is not bundled in this repository. For example:

```powershell
$env:PROJECT_JAVA_HOME = 'C:\path\to\jdk8u502'
$env:SYSTEMJ_LIB = 'D:\path\to\COMPSYS704_Lab_3\lib'
python tools\project.py test
python tools\project.py run --no-build
```

Alternatively pass `--java-home` and `--systemj-lib` to each command. The
launcher verifies the pinned JAR checksums before running. Eclipse is optional;
no IDE reconfiguration is required. See [the toolchain lock](toolchain/README.md).

See [the reset/twin integration notes](integration/RESET_TWIN_INTEGRATION.md)
for scope, reproducible live checks and limitations.

### Finishing stages and repeated orders (QA M2-8 / M2-9 / M2-10)

The overall view now shows the complete finishing sequence:
**Lid Loader -> Capper -> Labeller -> Bottle Unloader -> Sort / Pack**.
Labeller and Sort / Pack each have a status badge and a clickable read-only
detail view. The dashed downstream placeholder is no longer used. Sort / Pack
status comes from its actual Controller through the Coordinator; GP unloading
completion is not treated as proof that sorting has finished.

There is **no two-order submission limit**. Submit Order is disabled while one
order is active, then becomes available when that order completes. The previous
stall was a labeller completion-drain bug, not a POS limit: if `UNLOAD_READY`
was consumed before `MARK_LABELLED`, the labeller could remain DONE and refuse
the next bottle. It now rearms only after both outputs have been consumed,
in either order, and its ResourceTwin returns to READY at that same transition.
Live testing also exposed missed one-reaction machine commands/confirmations
and simulation batch requests. These now use bounded retained transmissions;
duplicate bottle identities cannot trigger the same physical operation twice.
You do not need Reset System between successfully completed orders. Resource
limits still apply (for example the lid magazine must eventually be refilled).

After pulling source changes, rebuild before using `--no-build`:

```powershell
python tools\project.py test
python tools\project.py run --no-build
```

Repeated-order acceptance (five mixed S/L orders, 15 bottle twins):

```powershell
python tools\project.py run --no-build --headless --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,2' --order-count 5 --duration 115 --expect-completions 5 --expect-workpieces 15
```

To check the actual GUI animation through all ten stages, leave the ABS window
open until this test stops its six runtimes automatically:

```powershell
python tools\project.py run --no-build --order 'PO0001|1|P1,L,60,40,3' --duration 120 --expect-completions 1 --expect-workpieces 3 --expect-visual-completions 3
```

The overview is a symbolic animation and can catch up after the real GP count
increases. A new batch replaces the previous batch's symbolic view; use the
BottleTwin and ResourceTwin tables for retained confirmed records.

This repository is the current development-stage integration baseline for the
Automated Bottling System (ABS). It contains M1's Swing POS, Coordinator and
display-only Visualisation; M2's Loader, Conveyor, Labeller, Unloader,
Transfer Fault Adapter and Digital Twin; and M3's Rotary Table, Lid Loader and
Fault Supervisor; and M4's two-size Filling, Capping and Sort/Pack
Controller/Plant modules. The unified Mock Controller is test-only.

The executable integration topology and receiver allocation are maintained in
[`integration/`](integration/), with the actual `.sysj`, `.xml` and Java
implementations as the source of truth. Proposed interface changes must be
agreed and applied consistently to source, XML and tests.

## Current integration status

- M1 remains three top-level components: POS, Coordinator and the display-only
  Visualisation IP. Bottle-size selection is a POS feature; the Coordinator
  retains that order data and owns whole-system reset orchestration.
- Conveyor and Rotary are independent Controllers. The current M1 production
  mapping uses `ConveyorControllerCD:11009` and
  `RotaryTableControllerCD:11003` with separate `CONVEYOR_*` and `ROTARY_*`
  status interfaces.
- `MockControllerCD` remains a regression fixture and is not a production
  substitute for the real Machine Controllers.
- M3 owns `RotaryTableControllerCD:11003`, `LidLoaderControllerCD:11006`,
  `RotaryTablePlantCD:12003`, `LidLoaderPlantCD:12006` and
  `FaultSupervisorCD:13003`.
- M2 owns implemented Loader, Conveyor, Labeller, Unloader, Transfer Fault
  Adapter and Digital Twin modules. Its canonical production mapping is
  `machines/transfer/member2_system.xml`.
- M4 owns implemented Filler A/B, Capper, Bottle Context Registry,
  Recognition Plant and Sort/Pack modules. Its canonical production mapping
  is `machines/filling_capping/member4_system.xml`.
- `ABSVisualisationPlantCD` is display-only. It does not control machines,
  actuators or physical Plant state.
- The obsolete combined `TransportControllerCD` / `TRANSPORT_*` status
  boundary is not part of the current M1 architecture.

## Draft PR member review

- [ ] M1: review Coordinator reset/batch delivery, ten-stage Swing overview,
  and read-only BottleTwin/ResourceTwin tables. This PR extends the current
  Swing UI; the separate, unmerged Web3D prototype is not imported. Agree its
  future telemetry/UI compatibility before combining those branches.
- [ ] M2: review label verification, bounded identity-preserving handoffs,
  repeated-order rearming, safe reset and both twin stores.
- [ ] M3: review simulated rotary/lid reset reconciliation, retained identity
  fences and confirmed LIDDED observations.
- [ ] M4: review safe filling/capping/sort reset, S/L context, confirmed twin
  observations and Sort/Pack telemetry.
- [ ] All members: rebuild, repeat the live checks, inspect cross-member
  interfaces and agree the documented simulation limits before marking ready.

The [verification notes](integration/RESET_TWIN_INTEGRATION.md) distinguish
historical `github_1` GUI checks from new team-checkout checks: 31 suites,
five consecutive mixed orders / 15 completed bottles, and active reset
followed by fresh production all passed on this integration branch.

## Start here

1. Read [`integration/README.md`](integration/README.md) for the integration
   topology, ownership boundaries and merge order.
2. Read [`tests/README.md`](tests/README.md) for the Java 8/SystemJ build and
   regression procedure.
3. Read [`machines/rotary_lid/README.md`](machines/rotary_lid/README.md) for
   the implemented M3 Controller/Plant and self-test details.

## M1 GP architecture

```text
M1 GP
|-- POS
|-- Coordinator
`-- Visualisation (IP)
```

### POS

The Swing POS owns order entry, automatic order IDs, multiple product rows,
the S/L bottle-size selector (`Small — 200 mL`, `Large — 500 mL`), liquid A/B
validation, submission of ORDER V1/V2-compatible payloads and
`ORDER_COMPLETE` display. Its **Reset System** button and confirmation dialog
are only the user entry point: POS sends `SYSTEM_RESET_REQUEST` and displays
reset progress/completion, but it does not reset M2, M3, M4, Visualisation or
Controller state directly.

### Coordinator

The Coordinator parses and validates orders, retains each product's recipe,
`sizeCode` and `capacityMl`, dispatches product batches, publishes the
simulation-only M4 batch request, polls Controller status, counts
`BOTTLE_DONE`, coordinates fault tolerance and sends `ORDER_COMPLETE`.
Whole-system reset orchestration remains inside this same Coordinator: it
clears M1-owned state, fans out the reset identity, waits at the M2/M3/M4 ACK
barrier and then sends `SYSTEM_RESET_COMPLETE`.

The Coordinator does not control Plant valves, motors or actuators. Mechanical
flow is owned by the relevant Machine Controllers. Bottle size is retained
order data, not a separate M1 subsystem, and reset orchestration is a
Coordinator responsibility, not a separate Reset Controller.

### Visualisation (IP)

The hierarchical Visualisation is an asynchronous, display-only observer. It
receives Coordinator telemetry, including `VIZ_SYSTEM_RESET`, but does not
issue machine commands or own reset orchestration.

The runtime relationship is:

```text
POS -> Coordinator -> Bottle Loader / Conveyor / Rotary Turntable
                  -> Filler A / Filler B / Lid Loader / Capper / Unloader
                  -> ABS Visualisation
                  -> M4 Recognition Simulator [simulation only]
```

`BOTTLE_DONE` is sent by Bottle Unloader after one finished bottle reaches the
collection stage. Capper completion alone does not complete the production
cycle.

For integrated simulation, each accepted product batch also publishes the
simulation-only signal `M4_SIM_BATCH_REQUEST:String` as
`<orderId>-P<two-digit product index>|<quantity>|<sizeCode>`. The Coordinator
sends three identical bounded copies, each PRESENT for 200 ms with 600 ms
ABSENT gaps between copies. This does not replace `START_ORDER` or change
Controller ownership. The current M4 `RecognitionSimulatorCD` consumes the
third field, de-duplicates identical retries, rejects reuse of a batch ID with
a different quantity or size, and emits bottles at the batch-specific size.
Use `xuqi_coordinator/coordinator.xml` together with
`machines/filling_capping/member4_simulation.xml`. With the canonical
`member4_system.xml`, the optional simulation output remains disconnected.

## Design basis

The 2026 Project Brief requires distinct Conveyor, Rotary Turntable, Filler and
Capper machines/clock-domains, plus Bottle Loader, Lid Loader and Bottle
Unloader. The team additionally uses Filler A and Filler B as two logical
Controllers for the required two-liquid product. That two-controller split is
a team design decision.

## Repository layout

```text
xuqi_pos/          POS implementation
xuqi_coordinator/  ABS Coordinator
common/            shared order/state helpers
visualisation/     display-only hierarchical Visualisation IP
tests/             regression-only tests and Mock
machines/transfer/          M2 production modules, tests and Digital Twin IP
machines/rotary_lid/        M3 production modules and self-tests
machines/filling_capping/   M4 production modules, models and self-tests
integration/                topology, port manifest and merge checklist
tools/                      structural integration validation
```

## POS order protocols

```text
ORDER V2 (new POS submissions):
orderId|productCount|productId,sizeCode,A%,B%,quantity;...

S = 200 mL
L = 500 mL

ORDER V1 (Coordinator backward compatibility):
orderId|productCount|productId,A%,B%,quantity;...

ORDER_COMPLETE:
orderId|COMPLETED|completionTimeSeconds
```

`OrderV1` remains frozen. A V1 product defaults to S/200 mL inside the
Coordinator; V2 carries the explicit product size. `START_ORDER`,
`FILL_A_RATIO` and `FILL_B_RATIO` retain their existing semantics.

The POS Reset System control sends a bounded String-valued
`SYSTEM_RESET_REQUEST` identity; it is only the user trigger. Coordinator
clears M1-owned state and fans the identity out as
`M2_SYSTEM_RESET`, `M3_SYSTEM_RESET`, `M4_SYSTEM_RESET` and
`VIZ_SYSTEM_RESET`. It reports `SYSTEM_RESET_COMPLETE` only after matching
M2/M3/M4 ACKs. This integration branch implements all three receivers with
simulation-safe reset barriers and retained stale-work tombstones. If a member
is missing or cannot confirm safety, the Coordinator correctly remains in
`RESET_PENDING_EXTERNAL_ACK`; receipt alone is not completion.

## Local Clock Domains and ports

| Component | Clock Domain | Receiver port |
| --- | --- | ---: |
| POS | `POSCD` | 11000 |
| Coordinator | `CoordinatorCD` | 11001 |
| Bottle Loader | `BottleLoaderControllerCD` | 11002 |
| Rotary Table | `RotaryTableControllerCD` | 11003 |
| Filler A | `FillerAControllerCD` | 11004 |
| Filler B | `FillerBControllerCD` | 11005 |
| Lid Loader | `LidLoaderControllerCD` | 11006 |
| Capper | `CapperControllerCD` | 11007 |
| ABS Visualisation | `ABSVisualisationPlantCD` | 11008 |
| Conveyor | `ConveyorControllerCD` | 11009 |
| Bottle Unloader | `BottleUnloaderControllerCD` | 11010 |
| Bottle Context Registry | `BottleContextRegistryCD` | 11011 |
| Sort / Pack | `SortPackControllerCD` | 11012 |
| Labeller | `LabellerControllerCD` | 11013 |
| Recognition Simulator (simulation only) | `RecognitionSimulatorCD` | 11014 |
| Rotary Table Plant | `RotaryTablePlantCD` | 12003 |
| Filler A Plant | `FillerAPlantCD` | 12004 |
| Filler B Plant | `FillerBPlantCD` | 12005 |
| Lid Loader Plant | `LidLoaderPlantCD` | 12006 |
| Capper Plant | `CapperPlantCD` | 12007 |
| Recognition Plant | `RecognitionPlantCD` | 12011 |
| Sort / Pack Plant | `SortPackPlantCD` | 12012 |
| M2 Transfer FT Adapter | `M2TransferFaultAdapterCD` | 13002 |
| Fault Supervisor | `FaultSupervisorCD` | 13003 |

All local mappings use `127.0.0.1` and the SystemJ
`SimpleServer`/`SimpleClient` pattern. The test Mock uses port 11002 for all
machine-facing inputs; that mapping is **TEST ONLY**.

## Reproducible regression test

The four-runtime test verifies:

```text
POS -> Coordinator -> Mock final path -> Bottle Unloader BOTTLE_DONE
 ^          |                                      |
 |          +-------> ABS Visualisation            |
 `---------------- ORDER_COMPLETE <----------------'
```

See [`tests/README.md`](tests/README.md) for compilation, startup order,
headless execution and expected regression evidence. This path validates M1
transport and state handling against the test-only Mock; real Controller/Plant
acceptance remains a separate cross-member integration activity.

Before a merge, run `python tools/validate_integration.py`. The structural
validator includes the canonical M1, M2, M3 and M4 production XML and checks
their registered receivers and required inputs.

## Prerequisite

The project toolchain is frozen to Eclipse Temurin OpenJDK `1.8.0_502`
(`Temurin-8.0.502+7`), `javac 1.8.0_502`, and the exact SystemJ JAR checksums
in [`toolchain/systemj-project.sha256`](toolchain/systemj-project.sha256).
Generated Java is a build artifact and must not be edited or committed.

Every member must run this check before compiling:

```bash
python3 tools/verify_project_toolchain.py \
  --java-home "/path/to/temurin-8" \
  --systemj-lib "/path/to/COMPSYS704_Project1_SystemJ_lib"
```

Continue only when it prints `PROJECT_TOOLCHAIN_OK`. See
[`toolchain/README.md`](toolchain/README.md) for the project-wide rule.
