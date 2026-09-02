# COMPSYS704 Project 1 - Development Integration Baseline

This repository is the current development-stage integration baseline for the
Automated Bottling System (ABS). It contains M1's Swing POS, Coordinator and
display-only Visualisation; M2's Loader, Conveyor, Labeller, Unloader,
Transfer Fault Adapter and Digital Twin; and M3's Rotary Table, Lid Loader and
Fault Supervisor. The unified Mock Controller is test-only; M4 production
Controller/Plant modules are not yet present.

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
- M2 production SystemJ/Java/XML is under `machines/transfer/`; M4 remains a
  documented integration slot pending peer source.
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
machines/transfer/          M2 production modules, tests and Digital Twin IP
machines/rotary_lid/        M3 production modules and self-tests
machines/filling_capping/   M4 integration slot (pending peer source)
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
| Bottle Context Registry | `BottleContextRegistryCD` | 11011 (proposed) |
| Sort / Pack | `SortPackControllerCD` | 11012 (proposed) |
| Labeller | `LabellerControllerCD` | 11013 |
| Rotary Table Plant | `RotaryTablePlantCD` | 12003 |
| Lid Loader Plant | `LidLoaderPlantCD` | 12006 |
| M2 Transfer FT Adapter | `M2TransferFaultAdapterCD` | 13002 |
| Fault Supervisor | `FaultSupervisorCD` | 13003 |

All local mappings use `127.0.0.1` and the Lab 3
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
validator now verifies the canonical M2 XML and continues to report M4 as a
pending peer until its production XML is supplied.

## Prerequisite

Use the Java 8 course environment and SystemJ compiler/runtime JARs supplied
with the COMPSYS704 Labs. Generated Java is a build artifact and must not be
edited or committed.
