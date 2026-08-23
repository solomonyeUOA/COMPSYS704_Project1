# COMPSYS704 Project 1 - Interface Freeze V1

This document is the primary GitHub-readable Coordinator interface contract
for Project 1 Integration V1. It is a team-agreed integration baseline, not a
forever-immutable API. Any change requires group agreement and coordinated
updates to SystemJ declarations, XML mappings, tests and documentation.

## Design basis

The 2026 Project Brief defines the Bottling Station as four constituent
machines: Conveyor, Rotary Turntable, Filler and Capper. Bottle Loader, Lid
Loader and Bottle Unloader are additional intelligent machines. Appendix 4
also requires separate Conveyor, Rotary, Filler and Capper clock-domains.

The project requires a two-liquid mix. This team keeps Filler A and Filler B as
two logical Machine Controllers. That split is a team design decision; it does
not merge Conveyor and Rotary Turntable or move Plant control into the
Coordinator.

## Frozen rules

- Do not rename signals or Clock Domains without group agreement.
- Do not change signal types, status codes or receiver ports independently.
- Do not add Plant actuator or sensor signals to the Coordinator interface.
- Do not add neighbouring-machine flow protocols to this contract.
- Keep `BOTTLE_DONE` as one collected finished bottle, not a Capper sub-step.

## Architecture boundary

```text
POS
 |
 | ORDER
 v
ABS Coordinator
 |-- Bottle Loader Controller
 |-- Conveyor Controller
 |-- Rotary Turntable Controller
 |-- Filler A Controller
 |-- Filler B Controller
 |-- Lid Loader Controller
 |-- Capper Controller
 `-- Bottle Unloader Controller
```

The Coordinator manages orders, batch parameters, machine status polling and
order completion. Machine Controllers own their Plant control sequences and
coordinate workpiece movement outside this interface.

## POS protocol V1

Both POS signals are valued SystemJ `String signal` declarations.

### ORDER

```text
orderId|productCount|product1;product2;product3;product4
```

Each product is:

```text
productId,liquidARatio,liquidBRatio,quantity
```

Example:

```text
PO001|2|P1,60,40,5;P2,30,70,3
```

Rules:

- `1 <= productCount <= 4`
- encoded product count must equal `productCount`
- both liquid ratios are integer percentages from 0 to 100
- `liquidARatio + liquidBRatio = 100`
- `quantity > 0`
- `orderId` and `productId` must not be empty
- POS validates before sending and Coordinator validates after receipt

### ORDER_COMPLETE

```text
orderId|COMPLETED|completionTime
```

`completionTime` is a non-negative integer number of seconds for the full
order. For course-runtime timing tolerance the Coordinator may transmit the
same payload up to three times. The POS de-duplicates these transport copies.

## Frozen Clock Domains and receiver ports

All local Integration V1 mappings use `127.0.0.1`. Each port belongs to the
receiving SystemJ runtime; inputs in the same runtime share its `SimpleServer`
port, following the Lab 3 pattern.

| Component | Frozen Clock Domain | Receiver port |
| --- | --- | ---: |
| POS | `POSCD` | 11000 |
| ABS Coordinator | `CoordinatorCD` | 11001 |
| Bottle Loader Controller | `BottleLoaderControllerCD` | 11002 |
| Conveyor Controller | `ConveyorControllerCD` | 11003 |
| Filler A Controller | `FillerAControllerCD` | 11004 |
| Filler B Controller | `FillerBControllerCD` | 11005 |
| Lid Loader Controller | `LidLoaderControllerCD` | 11006 |
| Capper Controller | `CapperControllerCD` | 11007 |
| Overall ABS Visualisation | `ABSVisualisationPlantCD` | 11008 |
| Rotary Turntable Controller | `RotaryTurntableControllerCD` | 11009 |
| Bottle Unloader Controller | `BottleUnloaderControllerCD` | 11010 |

## Coordinator outputs

| Receiver | Signal | Type | Meaning |
| --- | --- | --- | --- |
| POS | `ORDER_COMPLETE` | String | Completed-order payload defined above |
| Bottle Loader | `START_ORDER` | Integer | Bottle quantity for the current product batch |
| Filler A | `FILL_A_RATIO` | Integer | Liquid A percentage, 0-100 |
| Filler B | `FILL_B_RATIO` | Integer | Liquid B percentage, 0-100 |
| Bottle Loader | `LOADER_STATUS_REQUEST` | pure signal | Request current Loader status |
| Conveyor | `CONVEYOR_STATUS_REQUEST` | pure signal | Request current Conveyor status |
| Rotary Turntable | `ROTARY_STATUS_REQUEST` | pure signal | Request current Rotary status |
| Filler A | `FILLER_A_STATUS_REQUEST` | pure signal | Request current Filler A status |
| Filler B | `FILLER_B_STATUS_REQUEST` | pure signal | Request current Filler B status |
| Lid Loader | `LID_STATUS_REQUEST` | pure signal | Request current Lid Loader status |
| Capper | `CAPPER_STATUS_REQUEST` | pure signal | Request current Capper status |
| Bottle Unloader | `UNLOADER_STATUS_REQUEST` | pure signal | Request current Unloader status |

## Coordinator inputs

| Sender | Signal | Type | Meaning |
| --- | --- | --- | --- |
| POS | `ORDER` | String | Purchase-order payload defined above |
| Bottle Loader | `LOADER_STATUS` | Integer | Latest Loader status |
| Conveyor | `CONVEYOR_STATUS` | Integer | Latest Conveyor status |
| Rotary Turntable | `ROTARY_STATUS` | Integer | Latest Rotary status |
| Filler A | `FILLER_A_STATUS` | Integer | Latest Filler A status |
| Filler B | `FILLER_B_STATUS` | Integer | Latest Filler B status |
| Lid Loader | `LID_STATUS` | Integer | Latest Lid Loader status |
| Capper | `CAPPER_STATUS` | Integer | Latest Capper status |
| Bottle Unloader | `UNLOADER_STATUS` | Integer | Latest Unloader status |
| Bottle Unloader | `BOTTLE_DONE` | pure signal | One finished bottle reached collection and was unloaded |

## Status codes

| Value | Meaning |
| ---: | --- |
| 0 | IDLE |
| 1 | READY |
| 2 | BUSY |
| 3 | DONE |
| 4 | FAULT |

`WAITING` is only the initial GUI label before status is received. It is not a
Controller status value and there is no status code 5.

## Production XML mapping

The port is always the receiver's `SimpleServer` port.

| Sender | Receiver | Signal | Type | IP | Port |
| --- | --- | --- | --- | --- | ---: |
| `POSCD` | `CoordinatorCD` | `ORDER` | String | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `POSCD` | `ORDER_COMPLETE` | String | 127.0.0.1 | 11000 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `START_ORDER` | Integer | 127.0.0.1 | 11002 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `LOADER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11002 |
| `BottleLoaderControllerCD` | `CoordinatorCD` | `LOADER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `ConveyorControllerCD` | `CONVEYOR_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11003 |
| `ConveyorControllerCD` | `CoordinatorCD` | `CONVEYOR_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `RotaryTurntableControllerCD` | `ROTARY_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11009 |
| `RotaryTurntableControllerCD` | `CoordinatorCD` | `ROTARY_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `FillerAControllerCD` | `FILL_A_RATIO` | Integer | 127.0.0.1 | 11004 |
| `CoordinatorCD` | `FillerAControllerCD` | `FILLER_A_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11004 |
| `FillerAControllerCD` | `CoordinatorCD` | `FILLER_A_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `FillerBControllerCD` | `FILL_B_RATIO` | Integer | 127.0.0.1 | 11005 |
| `CoordinatorCD` | `FillerBControllerCD` | `FILLER_B_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11005 |
| `FillerBControllerCD` | `CoordinatorCD` | `FILLER_B_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `LidLoaderControllerCD` | `LID_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11006 |
| `LidLoaderControllerCD` | `CoordinatorCD` | `LID_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `CapperControllerCD` | `CAPPER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11007 |
| `CapperControllerCD` | `CoordinatorCD` | `CAPPER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `BottleUnloaderControllerCD` | `UNLOADER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11010 |
| `BottleUnloaderControllerCD` | `CoordinatorCD` | `UNLOADER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `BottleUnloaderControllerCD` | `CoordinatorCD` | `BOTTLE_DONE` | pure signal | 127.0.0.1 | 11001 |

## Multiple products and pipelining

The Coordinator validates and stores up to four products and dispatches one
product batch at a time. It emits `START_ORDER`, `FILL_A_RATIO` and
`FILL_B_RATIO` once per batch. Machine Controllers remain responsible for
pipelining multiple bottles. The next batch starts only after the current
batch reaches its required collected `BOTTLE_DONE` count. `ORDER_COMPLETE` is
emitted only after the final product batch finishes.

## Test-only mapping

`tests/coordinator_mock.xml` maps every machine-facing Coordinator output to a
single `MockControllerCD` on port 11002. This is **TEST ONLY** and does not
replace the production ports in `coordinator/coordinator.xml`.

## Migration note

The earlier combined Transport abstraction and its two status signals were
replaced by separate Conveyor and Rotary Turntable interfaces to match the
2026 brief. `BOTTLE_DONE` moved from Capper to Bottle Unloader so completion is
counted only after collection.
