# COMPSYS704 Project 1 - Development Integration Baseline

This repository is the current development-stage integration baseline for the
Automated Bottling System (ABS). It contains M1's Swing POS, Coordinator and
display-only Visualisation, M3's Rotary Table, Lid Loader and Fault Supervisor,
and M4's two-size Filling, Capping and Sort/Pack Controller/Plant modules. The
unified Mock Controller is test-only; real M2 Controller/Plant modules remain
owned by Member 2.

The executable integration topology and receiver allocation are maintained in
[`integration/`](integration/), with the actual `.sysj`, `.xml` and Java
implementations as the source of truth. Proposed interface changes must be
agreed and applied consistently to source, XML and tests.

## Current integration status

- M1 POS, Coordinator, transport-tolerance handling, display-only
  Visualisation and the current six-signal M1/M3 safety boundary are preserved.
- Conveyor and Rotary are independent Controllers. The current M1 production
  mapping uses `ConveyorControllerCD:11009` and
  `RotaryTableControllerCD:11003` with separate `CONVEYOR_*` and `ROTARY_*`
  status interfaces.
- `MockControllerCD` remains a regression fixture and is not a production
  substitute for the eight real Machine Controllers.
- M3 owns `RotaryTableControllerCD:11003`, `LidLoaderControllerCD:11006`,
  `RotaryTablePlantCD:12003`, `LidLoaderPlantCD:12006` and
  `FaultSupervisorCD:13003`.
- M4 owns implemented Filler A/B, Capper, Bottle Context Registry,
  Recognition Plant and Sort/Pack modules. Its canonical production mapping
  is `machines/filling_capping/member4_system.xml`.
- M2 transfer, loading, labelling and unloading modules remain pending peer
  dependencies; this also blocks the full physical end-to-end group run.
- `ABSVisualisationPlantCD` is display-only. It does not control machines,
  actuators or physical Plant state.
- The obsolete combined `TransportControllerCD` / `TRANSPORT_*` status
  boundary is not part of the current M1 architecture.

## Start here

1. Read [`integration/README.md`](integration/README.md) for the integration
   topology, ownership boundaries and merge order.
2. Read [`tests/README.md`](tests/README.md) for the Java 8/SystemJ build and
   regression procedure.
3. Read [`machines/rotary_lid/README.md`](machines/rotary_lid/README.md) for
   the implemented M3 Controller/Plant and self-test details.

## Architecture

```text
POS
 |
 v
Coordinator
 |
 +-- Bottle Loader
 +-- Conveyor
 +-- Rotary Turntable
 +-- Filler A
 +-- Filler B
 +-- Lid Loader
 +-- Capper
 +-- Bottle Unloader
 |
 +-- ABS Visualisation
```

The Coordinator handles orders, recipes, status supervision and completion
counts. It does not control Plant valves, motors or actuators. Mechanical flow
is owned by the relevant Machine Controllers.

`BOTTLE_DONE` is sent by Bottle Unloader after one finished bottle reaches the
collection stage. Capper completion alone does not complete the production
cycle.

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
machines/transfer/          M2 integration slot (pending peer source)
machines/rotary_lid/        M3 production modules and self-tests
machines/filling_capping/   M4 production modules, models and self-tests
integration/                topology, port manifest and merge checklist
tools/                      structural integration validation
```

## POS V1 protocol

```text
ORDER:
orderId|productCount|productId,A%,B%,quantity;...

ORDER_COMPLETE:
orderId|COMPLETED|completionTimeSeconds
```

The order protocol, product batching logic, `START_ORDER`, `FILL_A_RATIO` and
`FILL_B_RATIO` remain unchanged by the machine-architecture correction.

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

Before a merge, run `python3 tools/validate_integration.py`. The structural
validator includes M1, M3 and M4 production XML and reports only unresolved M2
receivers as peer implementation warnings.

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
