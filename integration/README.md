# Group 6 Integration Skeleton

## Purpose

This directory records the current production integration topology. M1, M2,
M3 and M4 production XML is registered in `system-manifest.json`; physical
cross-member acceptance remains an end-to-end integration task.

## M1 GP component ownership

### POS

POS owns Swing order entry, automatic order IDs, multiple product rows, the
S/L bottle-size selector, recipe validation, ORDER transport and
`ORDER_COMPLETE` display. The Reset System button is a user trigger only: POS
sends `SYSTEM_RESET_REQUEST` and displays the matching completion result.

### Coordinator

Coordinator owns order validation, recipe and bottle-specification retention,
batch dispatch, status polling, `BOTTLE_DONE` counting, FT coordination and
order completion. It also owns whole-system reset orchestration: M1 state
reset, M2/M3/M4/Visualisation fan-out, the external ACK barrier and
`SYSTEM_RESET_COMPLETE`. Bottle size remains order data carried through the
Coordinator; reset and size are not separate M1 subsystems.

### Visualisation (IP)

Visualisation is a read-only hierarchical observer. It consumes Coordinator
telemetry and reset notification without owning machine control or reset
orchestration. Reusable protocol/state/transport helpers remain in `common/`.

## M1 extension interfaces

These are extensions on the existing POS and Coordinator boundaries, not a
separate reset subsystem.

| Boundary | Signal | Ownership / purpose |
| --- | --- | --- |
| POS -> Coordinator | `ORDER` (V1/V2 payload) | POS selects S/L; Coordinator retains `sizeCode` and `capacityMl` |
| POS -> Coordinator | `SYSTEM_RESET_REQUEST` | POS user trigger; Coordinator begins orchestration |
| Coordinator -> POS | `SYSTEM_RESET_COMPLETE` | Sent only after the reset ACK barrier completes |
| Coordinator -> M2 | `M2_SYSTEM_RESET` | Coordinator reset fan-out |
| M2 -> Coordinator | `M2_SYSTEM_RESET_ACK` | Matching safe-state acknowledgement |
| Coordinator -> M3 | `M3_SYSTEM_RESET` | Coordinator reset fan-out |
| M3 -> Coordinator | `M3_SYSTEM_RESET_ACK` | Matching safe-state acknowledgement |
| Coordinator -> M4 | `M4_SYSTEM_RESET` | Coordinator reset fan-out |
| M4 -> Coordinator | `M4_SYSTEM_RESET_ACK` | Matching safe-state acknowledgement |
| Coordinator -> Visualisation | `VIZ_SYSTEM_RESET` | Display-state reset notification |
| Coordinator -> M4 simulator | `M4_SIM_BATCH_REQUEST` | Simulation-only `batchId|quantity|sizeCode` publication |

## Runtime topology

```text
POSCD (11000)
  <-> CoordinatorCD (11001)
        |-- BottleLoaderControllerCD (11002)       [M2 implemented]
        |-- ConveyorControllerCD (11009)           [M2 implemented]
        |-- RotaryTableControllerCD (11003)        [M3 implemented]
        |-- FillerAControllerCD (11004)            [M4 implemented]
        |-- FillerBControllerCD (11005)            [M4 implemented]
        |-- LidLoaderControllerCD (11006)           [M3 implemented]
        |-- CapperControllerCD (11007)              [M4 implemented]
        |-- BottleUnloaderControllerCD (11010)      [M2 implemented]
        `-- ABSVisualisationPlantCD (11008)         [M1 implemented]

M2TransferFaultAdapterCD (13002)                   [M2 implemented]
  <-> FaultSupervisorCD (13003)                    [M3 implemented]

RotaryTablePlantCD (12003)                         [M3 implemented]
  receives bottle-correlated P1/P2/P3/P4/P6 events

BottleLoaderPlantCD (12002)                        [M2 implemented]
ConveyorPlantCD (12009)                            [M2 implemented]
BottleUnloaderPlantCD (12010)                      [M2 implemented]
LabellerPlantCD (12013)                            [M2 implemented]
DigitalTwinCD / DigitalTwinViewerCD (14002 / 14003) [M2 implemented]

BottleContextRegistryCD (11011)                    [M4 implemented]
RecognitionPlantCD (12011)                         [M4 implemented]
SortPackControllerCD (11012)                       [M4 implemented]
SortPackPlantCD (12012)                            [M4 implemented]
FillerAPlantCD / FillerBPlantCD (12004 / 12005)    [M4 implemented]
CapperPlantCD (12007)                              [M4 implemented]

RecognitionSimulatorCD (11014)                    [simulation only]
  pending update: M1 M4_SIM_BATCH_REQUEST:String is batchId|quantity|sizeCode
```

`LabellerControllerCD:11013`, the four M2 Plant Clock Domains and
`DigitalTwinCD:14002` are implemented in `machines/transfer/`. M4's Registry,
Filling, Capping and Sort/Pack modules are implemented in
`machines/filling_capping/`. Their cross-member profiles and hand-offs still
require a physical end-to-end acceptance run.

## Simulation-only M1 -> M4 batch trigger

The six-runtime simulation uses `xuqi_coordinator/coordinator.xml` and
`machines/filling_capping/member4_simulation.xml`. For every product batch,
M1 derives a stable identity such as `PO0001-P01` and publishes
`PO0001-P01|10|S` on `M4_SIM_BATCH_REQUEST`. Member 4 must update
`RecognitionSimulatorCD` to validate the third field and map S/L to its
GEOM_S/GEOM_L profiles. After that update M4 will de-duplicate retries and emit
exactly `PO0001-P01-B001` through `PO0001-P01-B010`, then waits in `FINISHED`
for a different batch ID. A batch that stops on a context-distribution timeout
also releases the simulator, so the next batch ID is still accepted. A second product uses `PO0001-P02` and restarts its
bottle suffix at `B001`.

This link is environmental simulation orchestration only. It is absent from
the canonical `machines/filling_capping/member4_system.xml`, changes no
Controller signal, and grants M1 no M4 actuator ownership. The old
`m4.sim.quantity` property remains available only through the standalone Java
state-model entry point; integrated `RecognitionSimulatorCD` ignores it.

## Merge order

1. Keep M1, M2, M3 and M4 independent self-tests passing.
2. Run `tools/validate_integration.py` against all canonical production XML.
3. Run the M2/M3, M2/M4 and M3/M4 compatibility tests.
4. Run the M4 two-size demo and verify both capacity/geometry paths.
5. Run one bottle through P1 to P6, then a multi-bottle pipeline.
6. Inject each supported fault and verify bounded recovery, M1 HOLD and no
   unintended actuator command.

## Definition of done

- No two receiver Clock Domains own the same TCP port.
- Every Coordinator status request is read-only.
- `LOAD_BOTTLE`, `MARK_FILLED`, `LID_CYCLE_DONE`, `MARK_CAPPED`,
  `MARK_LABELLED` and `P6_CLEAR` preserve the matching bottle identity.
- P6 rotation remains blocked until both label verification and physical
  removal evidence are accepted.
- Duplicate, stale and wrong-bottle events cause no repeated physical work.
- Cross-Clock-Domain event hand-offs retain one payload for bounded retry,
  expose an ABSENT edge between PRESENT offers and de-duplicate by bottle ID.
- `BOTTLE_DONE` is emitted once by M2 after verified unloading.
- FaultSupervisor may request recovery, but only the owning Controller drives
  actuators and only M1 authorises global resume.

## Files that must not be merged as production evidence

- IDE metadata (`.idea/`, `*.iml`).
- Screenshots without the editable source diagram.
- Demo/mock XML presented as a production mapping.
- Local generated Java or class files.
