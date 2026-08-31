# COMPSYS704 Project 1 - Development Integration Baseline

This repository is the current development-stage integration baseline for the
Automated Bottling System (ABS). It contains the implemented Swing POS,
SystemJ ABS Coordinator, Coordinator-facing contracts, display-only ABS
Visualisation and regression tests. The unified Mock Controller is test-only;
real Machine Controller/Plant modules remain owned and integrated by their
respective team members.

The V1 freeze is a team-agreed baseline, not a forever-immutable API. Proposed
changes must be agreed and applied consistently to source, XML, tests and docs.

## Current integration status

- M1 POS, Coordinator, transport-tolerance handling, display-only
  Visualisation and the current six-signal M1/M3 safety boundary are preserved.
- Conveyor and Rotary are independent Controllers. The current M1 production
  mapping uses `ConveyorControllerCD:11009` and
  `RotaryTableControllerCD:11003` with separate `CONVEYOR_*` and `ROTARY_*`
  status interfaces.
- The available M3 Lid interface matches `LidLoaderControllerCD:11006` and
  `LID_STATUS_REQUEST` / `LID_STATUS`.
- An inspected M3 branch still uses the obsolete combined
  `TransportControllerCD` / `TRANSPORT_*` Rotary status boundary. That peer
  implementation must be updated on the M3 side before integration; M1 does
  not provide a legacy compatibility mapping.
- `MockControllerCD` remains a regression fixture and is not a production
  substitute for the eight real Machine Controllers.

## Start here

1. Read [`docs/INTERFACE_FREEZE_V1.md`](docs/INTERFACE_FREEZE_V1.md).
2. Read [`docs/VISUALISATION_INTERFACE_V1.md`](docs/VISUALISATION_INTERFACE_V1.md).
3. Controller owners read [`docs/CONTROLLER_IMPLEMENTATION_GUIDE.md`](docs/CONTROLLER_IMPLEMENTATION_GUIDE.md).
4. Use `COMPSYS704_Interface_V1_Integration_Test_Candidate_M4_V1_2_synced_fixed_28082026.xlsx`
   as the current quick reference.

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
xuqi_coordinator/  ABS Coordinator SystemJ source and production XML mapping
xuqi_pos/          POSCD plus handwritten Java Swing order-entry view
visualisation/     display Clock Domain, XML and Swing view
common/            ORDER parser and Coordinator shared state
docs/              interface contract and implementation guidance
machines/          integration locations for owner-supplied Controller/Plant modules
tests/             regression-only Mock, test XML and self-test
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
