# Group 6 Integration Skeleton

## Purpose

This directory records the current production integration topology. M1, M3
and M4 production XML is registered in `system-manifest.json`; unresolved M2
receivers remain explicit peer dependencies.

## Runtime topology

```text
POSCD (11000)
  <-> CoordinatorCD (11001)
        |-- BottleLoaderControllerCD (11002)       [M2 pending]
        |-- ConveyorControllerCD (11009)           [M2 pending]
        |-- RotaryTableControllerCD (11003)        [M3 implemented]
        |-- FillerAControllerCD (11004)            [M4 implemented]
        |-- FillerBControllerCD (11005)            [M4 implemented]
        |-- LidLoaderControllerCD (11006)           [M3 implemented]
        |-- CapperControllerCD (11007)              [M4 implemented]
        |-- BottleUnloaderControllerCD (11010)      [M2 pending]
        `-- ABSVisualisationPlantCD (11008)         [M1 implemented]

M2TransferFaultAdapterCD (13002)
  <-> FaultSupervisorCD (13003)                    [M3 implemented]

RotaryTablePlantCD (12003)                         [M3 implemented]
  receives bottle-correlated P1/P2/P3/P4/P6 events

BottleContextRegistryCD (11011)                    [M4 implemented]
RecognitionPlantCD (12011)                         [M4 implemented]
SortPackControllerCD (11012)                       [M4 implemented]
SortPackPlantCD (12012)                            [M4 implemented]
FillerAPlantCD / FillerBPlantCD (12004 / 12005)    [M4 implemented]
CapperPlantCD (12007)                              [M4 implemented]
```

`LabellerControllerCD:11013` and the M2 Loader/Conveyor/Unloader/Transfer
Adapter receivers remain pending. M4's Registry sends M2-owned load/unload
profiles and Sort/Pack expects the M2-owned downstream hand-off, so those
cross-member paths cannot be physically completed until M2 supplies matching
endpoints.

## Merge order

1. Keep M1, M3 and M4 independent self-tests passing.
2. M2 adds production source/XML under `machines/transfer/` and changes its
   receiver `status` values from `pending` to `implemented` in the manifest.
3. Run `tools/validate_integration.py` and all member self-tests.
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
