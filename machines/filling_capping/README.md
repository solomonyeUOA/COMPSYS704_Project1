# M4 Filling and Capping Integration Slot

M4 owns all code placed here. The final contribution should contain Filler A,
Filler B, Capper and any approved bottle-recognition, Registry or Sort/Pack
extension.

## Frozen core receiver ports

| Clock Domain | Port |
| --- | ---: |
| `FillerAControllerCD` | 11004 |
| `FillerBControllerCD` | 11005 |
| `CapperControllerCD` | 11007 |

## Required M3 hand-offs

- Accept `BOTTLE_AT_FILL(fullBottleContext)` at the Filler A receiver.
- Emit `MARK_FILLED(bottleId)` to `RotaryTablePlantCD:12003` only after both
  liquids reach their required quantity and sensor checks pass.
- Accept `BOTTLE_AT_CAP(fullBottleContext)` at the Capper receiver.
- Emit `MARK_CAPPED(bottleId)` to `RotaryTablePlantCD:12003` only after cap
  tightening and confirmation.

Status polling remains read-only. A timeout, overflow, missing context or
identity mismatch must not emit a completion event. Proposed Registry and
Sort/Pack interfaces must be frozen by the team before they are treated as
production boundaries.
