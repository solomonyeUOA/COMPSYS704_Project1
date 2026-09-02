# Group 6 Integration Skeleton

## Purpose

This directory records the current production integration topology. M1, M2,
M3 and M4 production XML is registered in `system-manifest.json`; physical
cross-member acceptance remains an end-to-end integration task.

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
```

`LabellerControllerCD:11013`, the four M2 Plant Clock Domains and
`DigitalTwinCD:14002` are implemented in `machines/transfer/`. M4's Registry,
Filling, Capping and Sort/Pack modules are implemented in
`machines/filling_capping/`. Their cross-member profiles and hand-offs still
require a physical end-to-end acceptance run.

## Merge order

1. Keep M1, M2, M3 and M4 independent self-tests passing.
2. Run `tools/validate_integration.py` against all canonical production XML.
3. Run the M2/M3 and M3/M4 compatibility tests.
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
- `BOTTLE_DONE` is emitted once by M2 after verified unloading.
- FaultSupervisor may request recovery, but only the owning Controller drives
  actuators and only M1 authorises global resume.

## Files that must not be merged as production evidence

- IDE metadata (`.idea/`, `*.iml`).
- Screenshots without the editable source diagram.
- Demo/mock XML presented as a production mapping.
- Local generated Java or class files.
