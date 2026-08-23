# COMPSYS704 Project 1 - Integration V1

This repository is the shared integration baseline for the Automated Bottling
System (ABS). It contains the Swing POS, SystemJ ABS Coordinator, frozen
Coordinator-facing interfaces, display-only ABS Visualisation and a test-only
Mock Controller. Real Machine Controller/Plant modules are still to be added
by their owners.

The V1 freeze is a team-agreed baseline, not a forever-immutable API. Proposed
changes must be agreed and applied consistently to source, XML, tests and docs.

## Start here

1. Read [`docs/INTERFACE_FREEZE_V1.md`](docs/INTERFACE_FREEZE_V1.md).
2. Read [`docs/VISUALISATION_INTERFACE_V1.md`](docs/VISUALISATION_INTERFACE_V1.md).
3. Controller owners read [`docs/CONTROLLER_IMPLEMENTATION_GUIDE.md`](docs/CONTROLLER_IMPLEMENTATION_GUIDE.md).
4. Use `COMPSYS704_Project1_Interface_V1.xlsx` as the quick reference.

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
coordinator/    ABS Coordinator SystemJ source and production XML mapping
pos/            POSCD plus handwritten Java Swing order-entry view
visualisation/  display Clock Domain, XML and Swing view
common/         ORDER parser and Coordinator shared state
docs/           interface contract and implementation guidance
machines/       real Controller/Plant modules added by their owners
tests/          test-only Mock, test XML and self-test
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
| Conveyor | `ConveyorControllerCD` | 11003 |
| Filler A | `FillerAControllerCD` | 11004 |
| Filler B | `FillerBControllerCD` | 11005 |
| Lid Loader | `LidLoaderControllerCD` | 11006 |
| Capper | `CapperControllerCD` | 11007 |
| ABS Visualisation | `ABSVisualisationPlantCD` | 11008 |
| Rotary Turntable | `RotaryTurntableControllerCD` | 11009 |
| Bottle Unloader | `BottleUnloaderControllerCD` | 11010 |

All local mappings use `127.0.0.1` and the Lab 3
`SimpleServer`/`SimpleClient` pattern. The test Mock uses port 11002 for all
machine-facing inputs; that mapping is **TEST ONLY**.

## Reproducible integration test

The four-runtime test verifies:

```text
POS -> Coordinator -> Mock final path -> Bottle Unloader BOTTLE_DONE
 ^          |                                      |
 |          +-------> ABS Visualisation            |
 `---------------- ORDER_COMPLETE <----------------'
```

See [`tests/README.md`](tests/README.md) for compilation, startup order,
headless execution and expected evidence for all eight machine states and
progress `0/2 -> 1/2 -> 2/2`.

## Prerequisite

Use the Java 8 course environment and SystemJ compiler/runtime JARs supplied
with the COMPSYS704 Labs. Generated Java is a build artifact and must not be
edited or committed.
