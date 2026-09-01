# COMPSYS704 Project 1 - Interface Freeze V1

This document is the primary GitHub-readable Coordinator interface contract
for Project 1 Integration V1. It records the current implemented integration
baseline verified against the latest SystemJ source and production XML. It is
a team-agreed integration baseline, not a forever-immutable API. Implemented
additions may be incorporated into V1 after integration review; any change
still requires group agreement and coordinated updates to SystemJ
declarations, XML mappings, tests and documentation.

The cross-member impact audit completed on 28 August 2026 synchronised the M1
production mapping with the assigned M2 Conveyor receiver and frozen M3
Rotary receiver. M2 and M3 production source/XML are now present in this
repository and pass their model/contract tests. M4 production source/XML is
still absent, so its contract alignment and peer implementation verification
remain separate claims.

## M3-facing V2.1 supersession

Effective 28 August 2026, the supplied
`COMPSYS704_M3_GP_IP_Integration_Interface_Contract_V2_1.docx` is the
authoritative `FROZEN` baseline only for the M3-facing boundaries it explicitly
defines: separated Conveyor/Rotary/Lid status, P1/P6 bottle-correlated
hand-offs, the M2 Transfer Fault Adapter protocol, M1/M3 safety coordination,
M3 receiver ports and M3-facing recovery policy. It supersedes the earlier M3
V1/proposal content for those boundaries only. Unrelated M1, M2 and M4
interfaces retain their existing authority and status.

Contract status and implementation status are deliberately separate. A
`FROZEN` row may still be unimplemented or blocked by a receiver port owned by
another member.

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
- Keep neighbouring-machine hand-offs out of `CoordinatorCD`; when recorded in
  this master contract, identify their actual machine/Plant owners.
- Keep `BOTTLE_DONE` as one collected finished bottle, not a Capper sub-step.

## Architecture boundary

```text
POS -- ORDER --> ABS Coordinator -- VIZ_* --> ABS Visualisation
 ^                    |                       (display only)
 |                    |<-- FT safety --> M3 FaultSupervisorCD
 |                    |-- Bottle Loader Controller
 |                    |-- Conveyor Controller
 |                    |-- Rotary Turntable Controller
 |                    |-- Filler A Controller
 |                    |-- Filler B Controller
 |                    |-- Lid Loader Controller
 |                    |-- Capper Controller
 |                    `-- Bottle Unloader Controller
 `-- ORDER_COMPLETE --'
```

The Coordinator manages orders, batch parameters, machine status polling and
order completion. Machine Controllers own their Plant control sequences and
coordinate workpiece movement outside this interface. The ABS Visualisation
receives Coordinator-aggregated status and batch progress only. It has no
control path to any Machine Controller and emits no production command.
`FaultSupervisorCD` may exchange only the frozen safety-coordination signals
with the Coordinator; neither side gains direct actuator authority.

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
- for course-runtime transport tolerance, the POS may transmit the same
  validated `ORDER` payload up to three times; the Coordinator accepts one
  copy and does not restart an already-active or already-completed order ID
- each transport copy is held `PRESENT` for a bounded wall-clock window
  (500 ms by default, configurable for tests), followed by an `ABSENT` gap;
  `ORDER` is not made permanently present

### ORDER_COMPLETE

```text
orderId|COMPLETED|completionTime
```

`completionTime` is a non-negative integer number of seconds for the full
order. For course-runtime timing tolerance the Coordinator may transmit the
same payload up to three times. Each copy uses the same bounded `PRESENT`
window principle (500 ms by default) and an inter-copy gap. The POS
de-duplicates these transport copies, ignores completion for a non-active
order, and prevents late copies from interfering with the next active order.

## Frozen Clock Domains and receiver ports

All local Integration V1 mappings use `127.0.0.1`. Each port belongs to the
receiving SystemJ runtime; inputs in the same runtime share its `SimpleServer`
port, following the Lab 3 pattern.

| Component | Frozen Clock Domain | Receiver port |
| --- | --- | ---: |
| POS | `POSCD` | 11000 |
| ABS Coordinator | `CoordinatorCD` | 11001 |
| Bottle Loader Controller | `BottleLoaderControllerCD` | 11002 |
| Rotary Table Controller | `RotaryTableControllerCD` | 11003 |
| Filler A Controller | `FillerAControllerCD` | 11004 |
| Filler B Controller | `FillerBControllerCD` | 11005 |
| Lid Loader Controller | `LidLoaderControllerCD` | 11006 |
| Capper Controller | `CapperControllerCD` | 11007 |
| Overall ABS Visualisation | `ABSVisualisationPlantCD` | 11008 |
| Conveyor Controller | `ConveyorControllerCD` | 11009 |
| Bottle Unloader Controller | `BottleUnloaderControllerCD` | 11010 |

The table above is the **current M1 production mapping** after the 28 August
cross-member audit. It follows the M2-assigned Conveyor receiver and the M3
V2.1 Rotary receiver. The production XML is aligned at the M1 sender, and the
matching M2/M3 receiver source/XML is now present.

| V2.1 receiver | Owner | Target port | Contract status | Current implementation / blocker |
| --- | --- | ---: | --- | --- |
| `CoordinatorCD` | M1 | 11001 | FROZEN | Implemented; all M3 safety inputs share the existing receiver. |
| `ConveyorControllerCD` | M2 | 11009 | M2 ASSIGNED | Implemented in `machines/transfer/member2_system.xml`; M1 polling mapping matches. |
| `RotaryTableControllerCD` | M3 | 11003 | FROZEN V2.1 | Implemented in `machines/rotary_lid/member3_system.xml`; M1 polling mapping matches. |
| `LidLoaderControllerCD` | M3 | 11006 | FROZEN | Current mapping already uses 11006. |
| `RotaryTablePlantCD` | M3 | 12003 | FROZEN | Implemented in the canonical M3 runtime. |
| `LidLoaderPlantCD` | M3 | 12006 | FROZEN | Implemented in the canonical M3 runtime. |
| `M2TransferFaultAdapterCD` | M2 | 13002 | M2 ASSIGNED | Implemented; receives `TRANSFER_RECOVERY_REQUEST` and preserves local actuator authority. |
| `FaultSupervisorCD` | M3 IP | 13003 | FROZEN | Implemented in the canonical M3 runtime; M1 outputs map here. |

The previous M1 mapping (`ConveyorControllerCD:11003` and
`RotaryTurntableControllerCD:11009`) is superseded. Port 11013 is assigned to
the M2 Labeller receiver for P6 machine-to-machine integration, but the
Coordinator-facing Labeller status interface remains only proposed; see the
cross-member audit section below.

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
| ABS Visualisation | `VIZ_LOADER_STATUS` | Integer | Display the latest Bottle Loader status |
| ABS Visualisation | `VIZ_CONVEYOR_STATUS` | Integer | Display the latest Conveyor status |
| ABS Visualisation | `VIZ_ROTARY_STATUS` | Integer | Display the latest Rotary Turntable status |
| ABS Visualisation | `VIZ_FILLER_A_STATUS` | Integer | Display the latest Filler A status |
| ABS Visualisation | `VIZ_FILLER_B_STATUS` | Integer | Display the latest Filler B status |
| ABS Visualisation | `VIZ_LID_STATUS` | Integer | Display the latest Lid Loader status |
| ABS Visualisation | `VIZ_CAPPER_STATUS` | Integer | Display the latest Capper status |
| ABS Visualisation | `VIZ_UNLOADER_STATUS` | Integer | Display the latest Bottle Unloader status |
| ABS Visualisation | `VIZ_REQUIRED_BOTTLES` | Integer | Display the current product-batch target |
| ABS Visualisation | `VIZ_COMPLETED_BOTTLES` | Integer | Display collected-bottle progress for the current product batch |
| M3 Fault Supervisor | `FT_SAFE_STOP_ACK` | String | Matching safe-stop acknowledgement; declared/mapped but not emitted until independent safe-stop evidence exists |
| M3 Fault Supervisor | `FT_RESUME_DECISION` | String | M1-only final `RESUME` or `HOLD` decision; declared/mapped but not emitted until the exact frozen M1 payload field order is supplied |

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
| Bottle Unloader | `BOTTLE_DONE` | pure signal | One finished bottle reached collection and was unloaded; one logical event is held `PRESENT` for a bounded window, followed by an `ABSENT` gap |
| M3 Fault Supervisor | `FT_FAULT_ALERT` | String | Record a validated event; alert alone does not stop or resume production |
| M3 Fault Supervisor | `FT_SAFE_STOP_REQUEST` | String | Enter M1 coordination HOLD and request safe stop; does not prove safe stop |
| M3 Fault Supervisor | `FT_RECOVERY_READY` | String | Record verified ready evidence; never auto-resume |
| M3 Fault Supervisor | `FT_RECOVERY_FAILED` | String | Record/escalate failure and retain M1 HOLD |

## Visualisation display-only boundary

The ten valued `Integer signal` interfaces above are implemented outputs from
`CoordinatorCD` and implemented inputs of `ABSVisualisationPlantCD`. All are
sent to `127.0.0.1:11008`.

The eight `VIZ_*_STATUS` values mirror the latest Controller status held by
the Coordinator and use the same status codes below. The two bottle-count
signals show the current product-batch target and collected-bottle progress.
The Visualisation does not poll or connect directly to Machine Controllers,
does not send feedback to the Coordinator, and cannot start or advance a
physical operation.

Clickable overview/detail views and their idealised local animations are
derived internally from these existing inputs. They do not create additional
SystemJ interfaces. No Label, Packaging, per-controller telemetry, 3D, or
other future visualisation signal is part of this baseline unless it appears
in both implemented SystemJ declarations and production XML.

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

The POS, Coordinator, ABS Visualisation, M2 and M3 rows are implemented at both
ends in the current production SystemJ/XML files. M4 Machine Controllers are
still pending peer source. The test-only Mock exercises the complete
Coordinator-facing direction set without replacing assigned production Clock
Domains or ports.

| Sender | Receiver | Signal | Type | IP | Port |
| --- | --- | --- | --- | --- | ---: |
| `POSCD` | `CoordinatorCD` | `ORDER` | String | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `POSCD` | `ORDER_COMPLETE` | String | 127.0.0.1 | 11000 |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_FAULT_ALERT` | String | 127.0.0.1 | 11001 |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_SAFE_STOP_REQUEST` | String | 127.0.0.1 | 11001 |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_RECOVERY_READY` | String | 127.0.0.1 | 11001 |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_RECOVERY_FAILED` | String | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `FaultSupervisorCD` | `FT_SAFE_STOP_ACK` | String | 127.0.0.1 | 13003 |
| `CoordinatorCD` | `FaultSupervisorCD` | `FT_RESUME_DECISION` | String | 127.0.0.1 | 13003 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `START_ORDER` | Integer | 127.0.0.1 | 11002 |
| `CoordinatorCD` | `BottleLoaderControllerCD` | `LOADER_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11002 |
| `BottleLoaderControllerCD` | `CoordinatorCD` | `LOADER_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `ConveyorControllerCD` | `CONVEYOR_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11009 |
| `ConveyorControllerCD` | `CoordinatorCD` | `CONVEYOR_STATUS` | Integer | 127.0.0.1 | 11001 |
| `CoordinatorCD` | `RotaryTableControllerCD` | `ROTARY_STATUS_REQUEST` | pure signal | 127.0.0.1 | 11003 |
| `RotaryTableControllerCD` | `CoordinatorCD` | `ROTARY_STATUS` | Integer | 127.0.0.1 | 11001 |
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
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_LOADER_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_CONVEYOR_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_ROTARY_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_FILLER_A_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_FILLER_B_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_LID_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_CAPPER_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_UNLOADER_STATUS` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_REQUIRED_BOTTLES` | Integer | 127.0.0.1 | 11008 |
| `CoordinatorCD` | `ABSVisualisationPlantCD` | `VIZ_COMPLETED_BOTTLES` | Integer | 127.0.0.1 | 11008 |

## Multiple products and pipelining

The Coordinator validates and stores up to four products and dispatches one
product batch at a time. It emits `START_ORDER`, `FILL_A_RATIO` and
`FILL_B_RATIO` once per batch. Machine Controllers remain responsible for
pipelining multiple bottles. The next batch starts only after the current
batch reaches its required collected `BOTTLE_DONE` count. `ORDER_COMPLETE` is
emitted only after the final product batch finishes.

Because `BOTTLE_DONE` is a pure signal with no bottle identifier, the sender
must make each logical event observable as one bounded `PRESENT` window and
must provide at least one `ABSENT` reaction before the next bottle event. The
current integration Mock uses a 500 ms default window. `CoordinatorCD`
edge-latches the window and increments the count only on its rising edge, so a
held signal is not counted twice and a late held event cannot count toward the
next order. The sender must not send retry copies of the same bottle event.

## Test-only mapping

`tests/coordinator_mock.xml` maps every machine-facing Coordinator output to a
single `MockControllerCD` on port 11002. This is **TEST ONLY** and does not
replace the production ports in `xuqi_coordinator/coordinator.xml`. The same
test mapping keeps all ten implemented Visualisation outputs directed to
`ABSVisualisationPlantCD` on port 11008.

`tests/coordinator_mock_no_visualisation.xml` is an intentional test-only
three-runtime variant. It omits the ten Visualisation mappings only when
`ABSVisualisationPlantCD` is deliberately not running; it does not redefine
the production interface.

## Cross-member impact audit

The latest M2/M3/M4 workbook, group report and available diagrams were checked
against the current M1 source/XML. The following results constrain M1:

- M2 Conveyor status polling now targets `ConveyorControllerCD:11009`.
- M3 Rotary status polling now targets `RotaryTableControllerCD:11003`.
- `LABELLER_STATUS_REQUEST` (pure, proposed M1 -> M2 at 11013),
  `LABELLER_STATUS` (Integer, proposed M2 -> M1 at 11001) and
  `VIZ_LABELLER_STATUS` (Integer, proposed M1 -> Visualisation at 11008) are
  not a frozen end-to-end M1 boundary. M2 now implements the proposed
  Controller-side request/status pair, but M1 and its Visualisation do not
  declare/map it. Team acceptance is still required before changing M1.
- `OrderV1.parse()` already enforces that both recipe values are integers in
  0..100 and that their sum is 100 before `CoordinatorStateV1` accepts and
  dispatches them. The M4 recipe-pair trust boundary therefore requires no
  Order V1, POS GUI or Coordinator logic change.
- M4 BottleContext, two-size processing and Sort/Pack preserve the frozen
  M1-facing ratio/status interface. They do not add an order field and do not
  change `BOTTLE_DONE`, which remains exclusively M2 Unloader-owned.
- Bottle-correlated P1/P2/P3/P4/P6 hand-offs, `UNLOAD_READY`,
  `BOTTLE_READY_FOR_SORT` and Digital Twin updates remain machine-to-machine or
  other-member-internal interfaces and are not declared in `CoordinatorCD`.

## Migration note

The earlier combined Transport abstraction and its two status signals were
replaced by separate Conveyor and Rotary Turntable interfaces to match the
2026 brief. `BOTTLE_DONE` moved from Capper to Bottle Unloader so completion is
counted only after collection. The ten implemented Coordinator-to-
Visualisation signals are now also part of this current V1 integration
baseline. No unimplemented Individual Project extension is included.

## M3-facing GP bottle-correlated hand-offs V2.1

These are machine/Plant hand-offs, not Coordinator signals. They are included
in the master contract for cross-team integration and must not be added to
`CoordinatorCD`.

| Stage | Sender | Receiver | Signal | Type | Receiver port | Contract / implementation status |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | M2 Entry | `RotaryTablePlantCD` | `LOAD_BOTTLE(bottleId)` | String | 12003 | FROZEN; NOT OWNED BY M1 |
| P2 | M4 Filler | `RotaryTablePlantCD` | `MARK_FILLED(bottleId)` | String | 12003 | FROZEN; NOT OWNED BY M1 |
| P3 | M3 Rotary | M3 Lid | `BOTTLE_AT_LID_POSITION(bottleId)` | String | M3 internal | FROZEN; NOT OWNED BY M1 |
| P3 | M3 Lid | M3 Rotary | `LID_CYCLE_DONE(bottleId)` | String | M3 internal | FROZEN; NOT OWNED BY M1 |
| P4 | M4 Capper | `RotaryTablePlantCD` | `MARK_CAPPED(bottleId)` | String | 12003 | FROZEN; NOT OWNED BY M1 |
| P6 | M3 Rotary | `LabellerControllerCD` | `BOTTLE_AT_LABEL(bottleId)` | String | 11013 | FROZEN; implemented and covered by the M2/M3 contract test |
| P6 | M2 Labeller | `RotaryTablePlantCD` | `MARK_LABELLED(bottleId)` | String | 12003 | FROZEN; NOT OWNED BY M1 |
| P6 | M2 Unloader | `RotaryTablePlantCD` | `P6_CLEAR(bottleId)` | String | 12003 | FROZEN; NOT OWNED BY M1 |

Labelling at P6 is always enabled in V2.1. M3 must block the next indexed
rotation until both `MARK_LABELLED(bottleId)` and `P6_CLEAR(bottleId)` match the
current P6 bottle. Stale, duplicate or wrong-bottle events are rejected. No
optional Label bypass is part of this baseline.

## M3-facing Fault-Tolerance V2.1

**CONTRACT STATUS: FROZEN**
**EFFECTIVE DATE: 28 August 2026**

This section supersedes the earlier M3-facing V1/proposal only for the safety
coordination, Transfer Adapter, recovery and correlation boundaries stated
below. It does not rewrite unrelated GP, Visualisation or other members'
internal interfaces.

### Repository implementation classification

| Item | Contract status | Implementation status in this repository |
| --- | --- | --- |
| Four M3-to-M1 FT inputs | FROZEN | IMPLEMENTED in `CoordinatorCD` declaration/state recording and mapped to `CoordinatorCD:11001` |
| `FT_SAFE_STOP_ACK` / `FT_RESUME_DECISION` outputs | FROZEN | DECLARED/MAPPED to `FaultSupervisorCD:13003`; intentionally not emitted because independent safe-stop evidence and exact M1 payload field order are unavailable |
| M1 order/batch HOLD structure | M1 implementation | IMPLEMENTED: alert-only is observational; safe-stop request/recovery failure holds new order and next-batch dispatch; recovery-ready does not clear HOLD |
| Physical safe-stop confirmation | Required before ACK | FROZEN CONTRACT — IMPLEMENTATION MISSING; no current M1-to-machine stop/confirmation boundary exists |
| Exact M1 FT String field order | Required for interoperable ACK/decision | CONFLICT/GAP: supplied V2.1 defines meaning and `RESUME`/`HOLD`, but no canonical M1 payload schema |
| `FaultSupervisorCD` peer | FROZEN at port 13003 | IMPLEMENTED by M3; still not owned by M1 |
| P1/P6 and M3 Rotary/Lid implementation | FROZEN | IMPLEMENTED by M2/M3 and cross-model tested; still not owned by M1 |
| M2 Transfer Fault Adapter | Signal/payload FROZEN | IMPLEMENTED by M2 at port 13002; still not owned by M1 |

Normal GP and Visualisation operation remains usable when the FT peer is
absent. `FT_SAFE_STOP_ACK` and `FT_RESUME_DECISION` use
`OptionalSimpleClient`, which does not attempt a connection while the valued
signal has never actually been emitted. The Coordinator never writes Plant
state or sends an actuator command.

### Frozen M1/M3 safety coordination

| Direction | Signal | Type | Receiver | Port | Meaning / current M1 behaviour |
| --- | --- | --- | --- | ---: | --- |
| M3 IP -> M1 | `FT_FAULT_ALERT` | String | `CoordinatorCD` | 11001 | Record validated event only; no automatic cancel, stop or resume |
| M3 IP -> M1 | `FT_SAFE_STOP_REQUEST` | String | `CoordinatorCD` | 11001 | Record matching request and enter M1 order/batch HOLD; request alone is not safe-stop evidence |
| M1 -> M3 IP | `FT_SAFE_STOP_ACK` | String | `FaultSupervisorCD` | 13003 | Emit only after the matching coordinated safe stop is independently established; current M1 does not emit it |
| M3 IP -> M1 | `FT_RECOVERY_READY` | String | `CoordinatorCD` | 11001 | Record verified ready evidence; HOLD remains and production does not auto-resume |
| M3 IP -> M1 | `FT_RECOVERY_FAILED` | String | `CoordinatorCD` | 11001 | Record/escalate failure and retain HOLD |
| M1 -> M3 IP | `FT_RESUME_DECISION` | String | `FaultSupervisorCD` | 13003 | M1 alone sends final `RESUME` or `HOLD` with event/reason; current M1 does not emit until the exact payload field order is frozen |

The supplied contract does not define the canonical field order for these six
M1 safety messages. Their payloads therefore remain opaque `String` values in
M1 code. The obsolete proposal-only names `FT_RECOVERY_APPROVAL` and
`FT_FAULT_ACK` are not declared or mapped as current V2.1 interfaces.

### Frozen M2 Transfer Fault Adapter protocol

All messages are valued SystemJ `String` signals using ASCII pipe delimiters.
A field must not contain `|`; unavailable optional values use `-`. Unknown
versions or malformed payloads are rejected without physical action.

| Step | Direction | Signal | Receiver / port | Contract / implementation status |
| ---: | --- | --- | --- | --- |
| 1 | M2 -> M3 IP | `TRANSFER_FAULT_EVENT` | `FaultSupervisorCD:13003` | FROZEN; NOT OWNED BY M1 |
| 2 | M3 IP -> M2 | `TRANSFER_RECOVERY_REQUEST` | `M2TransferFaultAdapterCD:13002` | FROZEN; implemented and parser-compatible with M3 V2.1 |
| 3 | M2 -> M3 IP | `TRANSFER_RECOVERY_ACK` | `FaultSupervisorCD:13003` | FROZEN; NOT OWNED BY M1 |
| 4 | M2 -> M3 IP | `TRANSFER_RECOVERY_RESULT` | `FaultSupervisorCD:13003` | FROZEN; NOT OWNED BY M1 |

Canonical V2.1 field order:

```text
TRANSFER_FAULT_EVENT
V2|eventId|sourceEpoch|subsystem|faultCode|severity|bottleId|stateVersion
V2|F021|E07|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B104|18

TRANSFER_RECOVERY_REQUEST
V2|eventId|sourceEpoch|action|attempt|expectedStateVersion
V2|F021|E07|RETRY_TRANSFER|1|18

TRANSFER_RECOVERY_ACK
V2|eventId|sourceEpoch|attempt|ack|reason|acceptedStateVersion
V2|F021|E07|1|ACCEPTED|route_clear|18

TRANSFER_RECOVERY_RESULT
V2|eventId|sourceEpoch|attempt|outcome|safeEvidence|serviceEvidence|resultingStateVersion
V2|F021|E07|1|SUCCESS|motor_off+occupancy_consistent|arrival_confirmed|19
```

### Recovery policy V2.1

- Attempt 1 is the maximum physical attempt in V2.1.
- Rotary `ALIGNMENT_TIMEOUT`: no automatic `REHOME`; motor off, then M1-held
  safe stop and manual position/bottle reconciliation without independent
  absolute-position evidence.
- Rotary `MOTOR_STALL` / `POSITION_SENSOR_FAILURE`: no automatic motion.
- Lid `MAGAZINE_EMPTY`: `WAIT_RESOURCE`; it consumes no attempt budget.
- Eligible Lid `PICK_TIMEOUT`: at most one `RETRY_PICK`, after actuator-home,
  no-lid-held and lid-available are independently confirmed.
- Lid `PLACEMENT_TIMEOUT` / `LID_SENSOR_FAULT`: no blind retry when placement
  is ambiguous.
- Eligible Transfer `ARRIVAL_TIMEOUT`: at most one `RETRY_TRANSFER`, after
  route-clear and consistent occupancy are confirmed; result requires arrival
  evidence.
- Transfer `DEPARTURE_TIMEOUT`, `PHOTO_EYE_FAILURE` or `POSITION_CONFLICT`: no
  blind restart; reconcile bottle identity/location first.
- Evidence must be independent; the failed sensor cannot verify itself.

### Correlation and idempotency V2.1

- Duplicate `eventId + attempt`: return the previous ACK/result and never
  repeat physical work.
- Inactive `sourceEpoch`: reject as `STALE_EPOCH`.
- Lower `stateVersion`: reject as `STALE_STATE` within the active epoch.
- Unexpected higher `stateVersion`: do not execute; request a fresh state
  snapshot and revalidate.
- ACK timeout: fail the request; do not silently resend a physical attempt.
- Result timeout or invalid evidence: report `FT_RECOVERY_FAILED`.
- At most one active recovery per subsystem.
- Critical faults pre-empt queued warnings deterministically.

### Reference safety sequence

1. Owning Controller/Adapter publishes one correlated fault event.
2. `FaultSupervisorCD` validates schema, identity, epoch/version and policy.
3. It sends `FT_FAULT_ALERT`; critical/ambiguous state also causes
   `FT_SAFE_STOP_REQUEST`.
4. M1 returns matching `FT_SAFE_STOP_ACK` only after safe stop is genuinely
   established. Current M1 cannot yet satisfy this condition.
5. For an eligible fault, M3 sends one request with `attempt=1`.
6. The owning Controller/Adapter returns `ACCEPTED` or `REJECTED` before any
   physical action.
7. It executes an accepted action once and returns independent evidence.
8. M3 validates evidence and sends `FT_RECOVERY_READY` or
   `FT_RECOVERY_FAILED`.
9. M1 alone sends final `RESUME` or `HOLD`; ready evidence never auto-resumes.

### Minimum V2.1 acceptance checks

1. Repeated status polling remains read-only.
2. Nominal POS, GP and Visualisation operation works with the FT peer absent.
3. Coordinator accepts all four mapped FT inputs on port 11001.
4. `FT_FAULT_ALERT` alone does not hold or resume production.
5. `FT_SAFE_STOP_REQUEST` produces no ACK before independent safe-stop
   evidence.
6. `FT_RECOVERY_READY` does not automatically resume.
7. Only M1 can issue the final `RESUME`/`HOLD` decision.
8. P6 blocks until matching Label and clear events and rejects stale/wrong ID.
9. Duplicate/stale recovery requests never repeat physical work.
10. No Coordinator or Fault Supervisor interface transfers actuator control.

### Explicit exclusions

No `LABEL_STATUS`, `LABEL_STATUS_REQUEST`, `VIZ_LABEL_STATUS`,
`LABELLER_STATUS`, `LABELLER_STATUS_REQUEST`, `VIZ_LABELLER_STATUS`, optional
Label bypass, Packaging, 3D Visualisation or additional per-controller
Visualisation telemetry is added. P1/P6 hand-offs do not by themselves freeze
a Coordinator-facing Labeller status interface. Advanced Visualisation remains
display-only.

## Legacy Fault-Tolerance proposal V1 (superseded history)

**STATUS: SUPERSEDED FOR THE M3-FACING V2.1 BOUNDARIES ABOVE**

The following material is retained only as migration/history for the earlier
`PROPOSED / INTEGRATION TEST CANDIDATE`. It is not the current M3-facing
contract. Where it conflicts with the frozen V2.1 section above, V2.1 wins.
In particular, old proposal-only signal names, V1 Transfer payloads, the
two-attempt policy and TBA `FaultSupervisorCD` port are obsolete for the
explicitly superseded boundaries. The frozen GP and Visualisation interfaces
outside those boundaries remain unchanged.

### Historical repository classification at proposal time

At the time of the proposal-only audit, the then-current Coordinator, POS and
Visualisation SystemJ sources, production XML, Mock Controller and test XML
contained no Fault-Tolerance Clock Domain, signal or mapping. This paragraph
records that historical audit state only; the implemented M1/M3 boundary is
described in the active V2.1 sections above.

| Fault-Tolerance item | Classification | Evidence |
| --- | --- | --- |
| Earlier standalone M3 V1 proposal | `SUPERSEDED HISTORY` | The proposal artifact was removed from the active baseline; its historical context is retained in this section only. |
| `FaultSupervisorCD`, `FaultSimulationPlantCD`, `FaultManagementGUI` | `PROPOSED ONLY` | No corresponding `.sysj`, `.xml` or `.java` implementation is present. |
| Coordinator-to-Supervisor and Supervisor-to-Coordinator signals | `PROPOSED ONLY` | At proposal-audit time, no declarations or XML mappings were present. |
| Rotary, Lid and Transfer fault/recovery adapter signals | `PROPOSED ONLY` | No declarations or XML mappings are present. |
| Fault-Tolerance GUI signal/API names | `PROPOSED ONLY` | The proposal names them, but no implementation transport or mapping exists. |
| `FaultEventV1`, `RecoveryRequestV1`, `RecoveryResultV1`, `CoordinatorDecisionV1` and the recovery policy | `PROPOSED ONLY` | Contract candidates only; no parser or recovery implementation is present. |
| Implemented Fault-Tolerance items | `IMPLEMENTED` | **None found at the proposal-audit time; see the active V2.1 implementation table above for the current M1 boundary.** |

These classifications must be reviewed again before this section is promoted
to an integration freeze.

### Historical proposed architecture and authority boundaries

The proposal assigns `CoordinatorCD` to M1, the Transfer Controller/Plant
boundary to M2, the Rotary/Lid GP boundary to M3, and
`FaultSupervisorCD`, `FaultSimulationPlantCD` and `FaultManagementGUI` to the
M3 IP. M4 is informed but has no mandatory Fault-Tolerance V1 interface
change.

`CoordinatorCD` retains order and batch orchestration, global pause authority,
global resume authority and final recovery/resume approval. A fault notification
or recovery result does not transfer those responsibilities to another Clock
Domain.

Machine Controllers retain actuator authority, sensor handling, local
safe-state handling and local recovery sequences. Every requested recovery is
an abstract intent; the owning Controller decides whether it is safe, performs
the physical sequence and returns a sensor-confirmed result.

`FaultSupervisorCD` is proposed to classify faults, apply bounded retries,
escalate failures, correlate events and coordinate recovery. It must not start
orders, directly operate actuators, directly modify Plant state or independently
resume global production.

`FaultSimulationPlantCD` is proposed for repeatable fault injection in explicit
test mode only. It must not remain active during production operation.

`FaultManagementGUI` is proposed to display fault status, event logs and
recovery progress, and to accept validated operator requests. It must never
send direct actuator commands.

Normal GP operation remains under the frozen architecture above. The system
must continue to run normally when this Fault-Tolerance extension is absent.

### Proposed Coordinator and Fault Supervisor interface

The proposed minimum integration set is `FT_FAULT_ALERT`,
`FT_SAFE_STOP_REQUEST`, `FT_RECOVERY_READY` and `FT_RECOVERY_APPROVAL`.
`FT_FAULT_ACK` and `FT_RECOVERY_FAILED` are optional for the first integration
demonstration.

| Sender | Receiver | Signal | SystemJ type | Proposed payload | Requirement | Receiver port |
| --- | --- | --- | --- | --- | --- | --- |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_FAULT_ALERT` | String | `FaultEventV1` | Minimum | TBA — pending integration-test port allocation |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_SAFE_STOP_REQUEST` | String | `FaultEventV1` | Minimum | TBA — pending integration-test port allocation |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_RECOVERY_READY` | String | `RecoveryResultV1` | Minimum | TBA — pending integration-test port allocation |
| `CoordinatorCD` | `FaultSupervisorCD` | `FT_RECOVERY_APPROVAL` | String | `CoordinatorDecisionV1` | Minimum | TBA — pending integration-test port allocation |
| `CoordinatorCD` | `FaultSupervisorCD` | `FT_FAULT_ACK` | String | `CoordinatorDecisionV1` using decision `ACK` | Optional | TBA — pending integration-test port allocation |
| `FaultSupervisorCD` | `CoordinatorCD` | `FT_RECOVERY_FAILED` | String | `RecoveryResultV1` | Optional | TBA — pending integration-test port allocation |

`FT_FAULT_ALERT` records and exposes a validated fault event; it does not pause
an order by itself. `FT_SAFE_STOP_REQUEST` asks the Coordinator to perform a
coordinated safe stop for a critical or unrecoverable event. The Coordinator
decides and executes the global pause. `FT_RECOVERY_READY` means that local
recovery has been sensor-confirmed and the affected Controller is in a safe
state; it is not an automatic resume. `FT_RECOVERY_APPROVAL` carries the
Coordinator's approve/deny decision for the referenced event and recovery or
resume step.

### Proposed Machine Controller adapters

All recovery requests below are abstract recovery intents. The owning Machine
Controller remains responsible for safety checks, actuator sequencing and
sensor-confirmed completion. `FaultSupervisorCD` never operates an actuator.

| Subsystem | Sender | Receiver | Signal | SystemJ type | Proposed payload | Receiver port |
| --- | --- | --- | --- | --- | --- | --- |
| Rotary | Owning Rotary Controller adapter | `FaultSupervisorCD` | `ROTARY_FAULT_EVENT` | String | `FaultEventV1` | TBA — pending integration-test port allocation |
| Rotary | `FaultSupervisorCD` | Owning Rotary Controller adapter | `ROTARY_RECOVERY_REQUEST` | String | `RecoveryRequestV1` | TBA — pending integration-test port allocation |
| Rotary | Owning Rotary Controller adapter | `FaultSupervisorCD` | `ROTARY_RECOVERY_RESULT` | String | `RecoveryResultV1` | TBA — pending integration-test port allocation |
| Lid | Owning Lid Controller adapter | `FaultSupervisorCD` | `LID_FAULT_EVENT` | String | `FaultEventV1` | TBA — pending integration-test port allocation |
| Lid | `FaultSupervisorCD` | Owning Lid Controller adapter | `LID_RECOVERY_REQUEST` | String | `RecoveryRequestV1` | TBA — pending integration-test port allocation |
| Lid | Owning Lid Controller adapter | `FaultSupervisorCD` | `LID_RECOVERY_RESULT` | String | `RecoveryResultV1` | TBA — pending integration-test port allocation |
| Transfer | Owning M2 Transfer Controller adapter | `FaultSupervisorCD` | `TRANSFER_FAULT_EVENT` | String | `FaultEventV1` | TBA — pending integration-test port allocation |
| Transfer | `FaultSupervisorCD` | Owning M2 Transfer Controller adapter | `TRANSFER_RECOVERY_REQUEST` | String | `RecoveryRequestV1` | TBA — pending integration-test port allocation |
| Transfer | Owning M2 Transfer Controller adapter | `FaultSupervisorCD` | `TRANSFER_RECOVERY_RESULT` | String | `RecoveryResultV1` | TBA — pending integration-test port allocation |

The exact adapter Clock Domain names remain a team integration decision. This
candidate does not rename or rebind the frozen GP Controller interfaces.

### Proposed Fault-Tolerance GUI interfaces

These interfaces are internal to the Fault-Tolerance IP and intentionally stay
separate from the Coordinator interface table.

| Sender | Receiver | Signal/API | Purpose | Transport/type | Receiver port |
| --- | --- | --- | --- | --- | --- |
| `FaultSupervisorCD` | `FaultManagementGUI` | `FT_GUI_STATUS` | Current Supervisor and subsystem state | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |
| `FaultSupervisorCD` | `FaultManagementGUI` | `FT_EVENT_LOG` | Event ID, subsystem, fault, severity, attempt and outcome | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |
| `FaultSupervisorCD` | `FaultManagementGUI` | `FT_RECOVERY_PROGRESS` | Requested action and verification progress | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |
| `FaultManagementGUI` | `FaultSupervisorCD` | `GUI_RETRY_REQUEST` | Validated operator request, never an actuator command | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |
| `FaultManagementGUI` | `FaultSupervisorCD` | `GUI_SAFE_STOP_REQUEST` | Operator request for a Coordinator-controlled safe stop | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |
| `FaultManagementGUI` | `FaultSimulationPlantCD` | `GUI_FAULT_INJECTION` | Repeatable fault injection; **test mode only** | TBA — proposal does not yet select Signal or API transport | TBA — pending integration-test port allocation |

The proposal does not yet define SystemJ declarations or XML mappings for the
GUI boundary, so this document does not invent them.

### Canonical proposed String payloads

Proposed cross-Clock-Domain Coordinator and Machine Adapter payloads use ASCII
pipe-delimited SystemJ `String` values. The schema name supplies the contract
version; mutable Java objects are not shared between independently deployed
components.

#### `FaultEventV1`

```text
eventId|subsystem|faultCode|severity|retryCount|workpieceId
F003|ROTARY|ALIGNMENT_TIMEOUT|CRITICAL|1|B017
```

#### `RecoveryRequestV1`

```text
eventId|subsystem|action|attempt
F003|ROTARY|REHOME|2
```

#### `RecoveryResultV1`

```text
eventId|subsystem|result|reason
F003|ROTARY|SUCCESS|alignment_confirmed
```

#### `CoordinatorDecisionV1`

```text
eventId|decision
F003|APPROVE_RECOVERY
```

### Allowed values and identity rules

| Field | Allowed values or rule |
| --- | --- |
| `subsystem` | `ROTARY`, `LID`, `TRANSFER` |
| `severity` | `WARNING`, `CRITICAL`, `UNRECOVERABLE` |
| `action` | `RETRY`, `REHOME`, `RECHECK_SENSOR`, `ISOLATE_WORKPIECE`, `SAFE_STOP` |
| `result` | `SUCCESS`, `FAILED`, `REJECTED`, `TIMEOUT` |
| `decision` | `ACK`, `APPROVE_RECOVERY`, `APPROVE_RESUME`, `DENY` |
| `eventId` | Unique and stable for the complete fault/recovery lifecycle. Duplicate messages must be handled idempotently. |
| `workpieceId` | Bottle/workpiece ID when available; use `-` when unavailable. |

### Parsing and failure handling

- Wrong field count: reject the payload and log `INVALID_PAYLOAD`.
- Unknown enum: reject the payload and log `UNSUPPORTED_VALUE`.
- Duplicate `eventId`: return the previous acknowledgement or result and do
  not repeat actuator work.
- Unavailable workpiece ID: accept `-`; do not invent an ID.
- Unsupported version: reject safely and request contract review.

Invalid, unknown or unsupported messages must never be converted into actuator
action.

### Proposed initial fault catalogue

| Subsystem | Proposed fault codes | Default candidate response |
| --- | --- | --- |
| Rotary | `MOTOR_STALL`, `ALIGNMENT_TIMEOUT`, `ALIGNMENT_SENSOR_INVALID` | Safe stop if motion state is uncertain; `REHOME` or `RECHECK_SENSOR`; verify alignment. |
| Lid | `MAGAZINE_EMPTY`, `PICK_TIMEOUT`, `PLACE_TIMEOUT`, `LID_SENSOR_INVALID` | Stop pick/place; refill or `RETRY`; verify picked/placed sensors. |
| Transfer | `ARRIVAL_TIMEOUT`, `DEPARTURE_TIMEOUT`, `PHOTOEYE_INCONSISTENT`, `POSITION_CONFLICT` | Stop the conveyor safely; `RECHECK_SENSOR` or `ISOLATE_WORKPIECE`; verify position. |

### Integration Test Candidate recovery policy

This policy is proposed and remains subject to integration-test validation.

- Maximum two automatic attempts per `eventId`.
- `WARNING`: a bounded local retry may proceed only when the owning Controller
  confirms a safe state.
- `CRITICAL`: request a Coordinator-controlled safe stop before recovery.
- `UNRECOVERABLE`: safe stop and manual intervention; no automatic retry.
- Recovery success requires a sensor-confirmed result from the owning
  Controller. Status polling alone is insufficient to complete recovery.
- Only the Coordinator authorises global resume after `FT_RECOVERY_READY`.
- Only one recovery may be active for an `eventId`.
- Simultaneous faults are queued or deterministically escalated.

### Proposed reference sequence

1. The owning Machine Controller emits `*_FAULT_EVENT`.
2. `FaultSupervisorCD` validates and classifies the event.
3. The Supervisor sends `FT_FAULT_ALERT`.
4. For a critical fault, it also sends `FT_SAFE_STOP_REQUEST`.
5. The Coordinator pauses or acknowledges as appropriate and sends
   `FT_RECOVERY_APPROVAL`.
6. The Supervisor sends the corresponding `*_RECOVERY_REQUEST`.
7. The owning Controller performs its locally safe recovery sequence.
8. The Controller returns `*_RECOVERY_RESULT` with sensor-confirmed evidence.
9. The Supervisor verifies the result and updates the GUI.
10. The Supervisor sends `FT_RECOVERY_READY` or the optional
    `FT_RECOVERY_FAILED`.
11. The Coordinator decides whether and when global production resumes.

### Safety and ownership boundary

- The Fault-Tolerance IP never writes Machine Plant Java state directly.
- The GUI never directly operates an actuator.
- `FaultSupervisorCD` never directly operates an actuator.
- The Fault-Tolerance IP does not own or emit `START_ORDER`.
- The Fault-Tolerance IP does not own or emit `ORDER_COMPLETE`.
- Critical fault notification requests a safe stop; it does not mean the
  system is already stopped.
- Recovery success requires owning-Controller sensor confirmation.
- All retries are bounded; repeated failure is escalated.
- `GUI_FAULT_INJECTION` is available in explicit test mode only.
- Normal frozen GP operation must remain possible when the Fault-Tolerance IP
  is absent.

### Historical port and XML allocation rule (superseded)

At proposal time, every new Fault-Tolerance receiver port was
`TBA — pending integration-test port allocation`. Receiver owners must select
unused, non-conflicting ports before the first cross-team test. No currently
occupied frozen receiver port may be reused or rebound; this includes the
current GP/Visualisation receiver range `11000` through `11010`, together with
the proposal-noted occupied ports `12003` and `12006`.

No Fault-Tolerance SystemJ declaration or production/test XML mapping was
added by that proposal-only revision. The active V2.1 section above records
the later frozen port 13003 and current M1 declarations/mappings.

### Historical promotion path (superseded)

**Historical status:** `PROPOSED / INTEGRATION TEST CANDIDATE`

**After receiver-port allocation and first cross-team interface
confirmation:** `FROZEN FOR INTEGRATION TEST`. At that stage, signal names,
types, directions, payload structures and receiver ports remain stable for
that test cycle unless the team explicitly revises the baseline.

**After successful and stable integration testing:** `FROZEN`. If an
incompatible interface change is later required, create a V1.1 or V2 revision
instead of silently changing the frozen baseline.

### Explicit exclusions

This candidate adds no Label, Packaging, 3D Visualisation or additional
per-Controller Visualisation telemetry interface. Such interfaces require
independent implementation and team approval before they can enter the master
contract.

## Revision history

- V1 initial baseline: POS, Coordinator, machine-facing status/recipe/order
  interfaces and display-only Visualisation.
- V1 current implemented update: transport-tolerant `ORDER` and
  `ORDER_COMPLETE`, current ten Visualisation signals, and current source/XML
  verification.
- M3-facing V2.1 supersession, effective 28 August 2026: frozen separated
  Rotary/Conveyor/Lid status ownership, P1/P6 bottle-correlated hand-offs,
  Transfer four-stage V2 protocol, M1/M3 safety signal names,
  `FaultSupervisorCD:13003`, one-attempt recovery policy and correlation rules.
  M2-owned Conveyor, Labeller and Transfer Adapter receiver ports are recorded;
  unverified peer implementations remain explicit dependencies.
- V1 cross-member implementation sync, 28 August 2026: M1 production XML now
  targets `ConveyorControllerCD:11009` and `RotaryTableControllerCD:11003`;
  the recipe-pair invariant is confirmed in current M1 validation; proposed
  Labeller status/Visualisation telemetry remains unimplemented.
- M2 implementation sync, 2 September 2026: Loader, Conveyor, Labeller,
  Unloader, their Plant Clock Domains, `M2TransferFaultAdapterCD` and the
  read-only Digital Twin are present in `machines/transfer/`. M2/M3 P1/P6 and
  V2.1 payload compatibility tests pass. The M1/VIZ Labeller monitoring
  extension and real M4 peers remain open integration gates.
