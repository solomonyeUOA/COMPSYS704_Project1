# Group 6 Integration Skeleton

## Purpose

This directory defines the shape of the future `main` branch. It does not
claim that missing peer Controllers are implemented. Each member copies their
tested production files into the assigned directory and satisfies the frozen
receiver and hand-off contract before merge.

## Runtime topology

```text
POSCD (11000)
  <-> CoordinatorCD (11001)
        |-- BottleLoaderControllerCD (11002)       [M2 pending]
        |-- ConveyorControllerCD (11009)           [M2 pending]
        |-- RotaryTableControllerCD (11003)        [M3 implemented]
        |-- FillerAControllerCD (11004)            [M4 pending]
        |-- FillerBControllerCD (11005)            [M4 pending]
        |-- LidLoaderControllerCD (11006)           [M3 implemented]
        |-- CapperControllerCD (11007)              [M4 pending]
        |-- BottleUnloaderControllerCD (11010)      [M2 pending]
        `-- ABSVisualisationPlantCD (11008)         [M1 implemented]

M2TransferFaultAdapterCD (13002)
  <-> FaultSupervisorCD (13003)                    [M3 implemented]

RotaryTablePlantCD (12003)                         [M3 implemented]
  receives bottle-correlated P1/P2/P3/P4/P6 events
```

`LabellerControllerCD:11013`, M4 Registry/SortPack and their Plant ports are
listed in the manifest, but remain proposed or peer-owned until their source
branches are merged and accepted.

## Merge order

1. Merge this skeleton after M1 and M3 tests pass independently.
2. M2 adds production source/XML under `machines/transfer/` and changes its
   receiver `status` values from `pending` to `implemented` in the manifest.
3. M4 adds production source/XML under `machines/filling_capping/` and updates
   its manifest entries.
4. Run `tools/validate_integration.py` and all member self-tests.
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
