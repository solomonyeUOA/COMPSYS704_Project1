# COMPSYS704 Project 1 - Interface Freeze V1

This document is the primary GitHub-readable interface contract for Project 1
Integration V1. The existing Excel workbook is retained as the original
Coordinator-to-Controller reference.

## Frozen rules

**INTERFACE FREEZE V1**

- Do not rename signals.
- Do not change signal types.
- Do not change status codes.
- Do not change ports without group agreement.
- Do not add Plant control signals to the Coordinator.
- Do not add Controller-to-Controller protocols to this contract.

Any proposed change must be agreed by the group before implementation starts.

The display-only Coordinator interface added later is documented separately in
`VISUALISATION_INTERFACE_V1.md`. It does not modify this Controller freeze.

## Architecture boundary

```text
POS
 |
 | ORDER
 v
ABS Coordinator
 |-- Bottle Loader Controller
 |-- Conveyor / Turntable Controller
 |-- Filler A Controller
 |-- Filler B Controller
 |-- Lid Loader Controller
 `-- Capper / Final Controller
```

The Coordinator manages orders, production parameters, machine status polling
and order completion. Machine Controllers own their Plant control sequences.

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
- the number of encoded products must equal `productCount`
- `liquidARatio` and `liquidBRatio` are integer percentages from 0 to 100
- `liquidARatio + liquidBRatio = 100`
- `quantity > 0`
- `orderId` and `productId` must not be empty
- POS validates before sending; Coordinator validates again after receipt

### ORDER_COMPLETE

```text
orderId|COMPLETED|completionTime
```

`completionTime` is a non-negative integer number of seconds for the full
order. Example:

```text
PO001|COMPLETED|25
```

For local course-runtime testing, the Coordinator may transmit this identical
payload up to three times to tolerate an isolated `SimpleClient` connection
timing loss. All copies represent one logical completion; the POS de-duplicates
them by accepting and printing the first valid completion only. This adds no
signal and does not change the frozen payload.

## Frozen Clock Domains and receiver ports

All Integration V1 mappings use `127.0.0.1` for local development. A port is
owned by the receiving SystemJ runtime; multiple input signals in that runtime
share its `SimpleServer` port, following the Lab 3 pattern.

| Component | Frozen Clock Domain | Receiver port |
| --- | --- | ---: |
| POS | `POSCD` | 11000 |
| ABS Coordinator | `CoordinatorCD` | 11001 |
| Bottle Loader Controller | `BottleLoaderControllerCD` | 11002 |
| Conveyor / Turntable Controller | `TransportControllerCD` | 11003 |
| Filler A Controller | `FillerAControllerCD` | 11004 |
| Filler B Controller | `FillerBControllerCD` | 11005 |
| Lid Loader Controller | `LidLoaderControllerCD` | 11006 |
| Capper / Final Controller | `CapperControllerCD` | 11007 |
| Overall ABS Visualisation | `ABSVisualisationPlantCD` | 11008 |

## Coordinator outputs

| Receiver | Signal | Type | Meaning |
| --- | --- | --- | --- |
| POS | `ORDER_COMPLETE` | String | Completed-order payload defined above |
| Bottle Loader | `START_ORDER` | Integer | Number of bottles in the current product batch |
| Filler A | `FILL_A_RATIO` | Integer | Liquid A percentage, 0-100 |
| Filler B | `FILL_B_RATIO` | Integer | Liquid B percentage, 0-100 |
| Bottle Loader | `LOADER_STATUS_REQUEST` | pure signal | Request current Loader status |
| Transport | `TRANSPORT_STATUS_REQUEST` | pure signal | Request current Conveyor/Turntable status |
| Filler A | `FILLER_A_STATUS_REQUEST` | pure signal | Request current Filler A status |
| Filler B | `FILLER_B_STATUS_REQUEST` | pure signal | Request current Filler B status |
| Lid Loader | `LID_STATUS_REQUEST` | pure signal | Request current Lid Loader status |
| Capper | `CAPPER_STATUS_REQUEST` | pure signal | Request current Capper status |

## Coordinator inputs

| Sender | Signal | Type | Meaning |
| --- | --- | --- | --- |
| POS | `ORDER` | String | Purchase-order payload defined above |
| Bottle Loader | `LOADER_STATUS` | Integer | Latest Loader status |
| Transport | `TRANSPORT_STATUS` | Integer | Latest Conveyor/Turntable status |
| Filler A | `FILLER_A_STATUS` | Integer | Latest Filler A status |
| Filler B | `FILLER_B_STATUS` | Integer | Latest Filler B status |
| Lid Loader | `LID_STATUS` | Integer | Latest Lid Loader status |
| Capper | `CAPPER_STATUS` | Integer | Latest Capper status |
| Capper / Final Controller | `BOTTLE_DONE` | pure signal | One bottle completed the whole line |

## Status codes

| Value | Meaning |
| ---: | --- |
| 0 | IDLE |
| 1 | READY |
| 2 | BUSY |
| 3 | DONE |
| 4 | FAULT |

## XML mapping and ports

The port below is always the receiver's `SimpleServer` port.

| Sender | Receiver | Signal | Type | IP | Port |
| --- | --- | --- | --- | --- | ---: |
| `POSCD` | `CoordinatorCD` | `ORDER` | String | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `POSCD` | `ORDER_COMPLETE` | String | 127.0.0.1 | 11000 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `START_ORDER` | Integer | 127.0.0.1 | 11002 |
| `CoordinatorCD` | `FillerAControllerCD` | `FILL_A_RATIO` | Integer | 127.0.0.1 | 11004 |
| `CoordinatorCD` | `FillerBControllerCD` | `FILL_B_RATIO` | Integer | 127.0.0.1 | 11005 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `LOADER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11002 |
| `BottleLoaderControllerCD` | `CoordinatorCD` | `LOADER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `TransportControllerCD` | `TRANSPORT_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11003 |
| `TransportControllerCD` | `CoordinatorCD` | `TRANSPORT_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `FillerAControllerCD` | `FILLER_A_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11004 |
| `FillerAControllerCD` | `CoordinatorCD` | `FILLER_A_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `FillerBControllerCD` | `FILLER_B_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11005 |
| `FillerBControllerCD` | `CoordinatorCD` | `FILLER_B_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `LidLoaderControllerCD` | `LID_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11006 |
| `LidLoaderControllerCD` | `CoordinatorCD` | `LID_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `CapperControllerCD` | `CAPPER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11007 |
| `CapperControllerCD` | `CoordinatorCD` | `CAPPER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CapperControllerCD` | `CoordinatorCD` | `BOTTLE_DONE` | pure signal | 127.0.0.1 | 11001 |

## Multiple products

The Coordinator validates and stores up to four products. Integration V1
dispatches one product batch at a time. For each batch it emits `START_ORDER`,
`FILL_A_RATIO` and `FILL_B_RATIO` once, while the machine Controllers remain
responsible for pipelining multiple bottles. When the current batch reaches its
required `BOTTLE_DONE` count, the next product is dispatched. `ORDER_COMPLETE`
is emitted only after the final product batch finishes.

## Test-only mapping

`tests/coordinator_mock.xml` maps all future Controller-facing Coordinator
outputs to one `MockControllerCD` on port 11002. This exists only to test the
frozen signals before real Controllers are available. It does not replace the
production mappings in `coordinator/coordinator.xml`.
