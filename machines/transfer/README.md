# M2 Transfer Subsystem Integration Slot

M2 owns all code placed here. The final contribution should contain the
Loader, Conveyor, Labeller, Bottle Unloader, associated Plant models and
`M2TransferFaultAdapterCD`.

## Required frozen receiver ports

| Clock Domain | Port |
| --- | ---: |
| `BottleLoaderControllerCD` | 11002 |
| `ConveyorControllerCD` | 11009 |
| `BottleUnloaderControllerCD` | 11010 |
| `LabellerControllerCD` | 11013 |
| `M2TransferFaultAdapterCD` | 13002 |

## Required M3 hand-offs

- Emit `LOAD_BOTTLE(bottleId)` to `RotaryTablePlantCD:12003` only after P1 is
  stable and the identity is committed.
- Accept `BOTTLE_AT_LABEL(bottleId)` at `LabellerControllerCD:11013`.
- Emit `MARK_LABELLED(bottleId)` after matching label verification.
- Emit `P6_CLEAR(bottleId)` only after physical removal and empty-position
  evidence.
- Emit `BOTTLE_DONE` once to `CoordinatorCD:11001` after verified unloading.
- Implement the V2.1 four-message FT exchange around existing M2 Controllers;
  the Adapter does not replace their actuator or safety authority.

Do not bind M3 ports 11003, 11006, 12003, 12006 or 13003.
