# COMPSYS704 Project 1 - Development Integration Baseline

This repository is the current development-stage integration baseline for the
Automated Bottling System (ABS). It contains the implemented Swing POS,
SystemJ ABS Coordinator, Coordinator-facing contracts, display-only ABS
Visualisation and regression tests. The unified Mock Controller is test-only;
real Machine Controller/Plant modules remain owned and integrated by their
respective team members.

The authoritative quick-reference interface is
[`COMPSYS704_Interface_V1_Integration_Test_Candidate_M4_V1_2_synced_fixed_28082026.xlsx`](COMPSYS704_Interface_V1_Integration_Test_Candidate_M4_V1_2_synced_fixed_28082026.xlsx)
at the repository root. Current supporting contracts are in
[`docs/interfaces/`](docs/interfaces/). Files in
[`docs/archive/`](docs/archive/) are historical/reference-only and are **not**
authoritative.

The V1 freeze is a team-agreed baseline, not a forever-immutable API. Proposed
changes must be agreed and applied consistently to source, XML, tests and docs.

## Current integration status

- M1 POS, Coordinator, transport-tolerance handling, display-only
  Visualisation and the current six-signal M1/M3 safety boundary are preserved.
- Conveyor and Rotary are independent Controllers. The current M1 production
  mapping uses `ConveyorControllerCD:11009` and
  `RotaryTableControllerCD:11003` with separate `CONVEYOR_*` and `ROTARY_*`
  status interfaces.
- `MockControllerCD` remains a regression fixture and is not a production
  substitute for the eight real Machine Controllers.
- `ABSVisualisationPlantCD` is display-only. It does not control machines,
  actuators or physical Plant state.
- The obsolete combined `TransportControllerCD` / `TRANSPORT_*` status
  boundary is not part of the current M1 architecture.

## Start here

1. Use
   [`COMPSYS704_Interface_V1_Integration_Test_Candidate_M4_V1_2_synced_fixed_28082026.xlsx`](COMPSYS704_Interface_V1_Integration_Test_Candidate_M4_V1_2_synced_fixed_28082026.xlsx)
   as the authoritative quick reference.
2. Read [`docs/interfaces/INTERFACE_FREEZE_V1.md`](docs/interfaces/INTERFACE_FREEZE_V1.md).
3. Read [`docs/interfaces/XUQI_M1_TEAM_INTEGRATION_CONTRACT_V1.md`](docs/interfaces/XUQI_M1_TEAM_INTEGRATION_CONTRACT_V1.md).
4. Read [`docs/interfaces/VISUALISATION_INTERFACE_V1.md`](docs/interfaces/VISUALISATION_INTERFACE_V1.md).
5. Controller owners read
   [`docs/guides/CONTROLLER_IMPLEMENTATION_GUIDE.md`](docs/guides/CONTROLLER_IMPLEMENTATION_GUIDE.md).

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
machines/          integration locations for team-owned machine modules

docs/interfaces/   current interface contracts
docs/guides/       implementation guidance
docs/diagrams/     current M1/IP diagrams
docs/reports/      report deliverables
docs/archive/      superseded/reference-only material
```

The master interface workbook remains at the repository root. Do not use an
archived workbook or reference diagram as the current integration contract.

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

## Prerequisite

Use the Java 8 course environment and SystemJ compiler/runtime JARs supplied
with the COMPSYS704 Labs. Generated Java is a build artifact and must not be
edited or committed.
