# ABS Visualisation Interface V1

This document defines the display-only interface from `CoordinatorCD` to the
Overall ABS Visualisation Plant. The Visualisation never connects directly to
Machine Controllers and contains no Plant actuator logic.

## Architecture boundary

```text
Eight Machine Controllers / test Mock
                 |
                 | STATUS signals
                 v
           CoordinatorCD
                 |
                 | VIZ_* display signals
                 v
    ABSVisualisationPlantCD
                 |
                 v
           Swing status view
```

## Clock Domain and receiver mapping

```text
Clock Domain: ABSVisualisationPlantCD
Class:        ABSVisualisationPlant
IP:           127.0.0.1
Port:         11008
```

All ten signals use the Lab 3 `SimpleClient` / `SimpleServer` XML pattern.

## Coordinator outputs

| Signal | Type | Meaning |
| --- | --- | --- |
| `VIZ_LOADER_STATUS` | Integer | Latest Bottle Loader status |
| `VIZ_CONVEYOR_STATUS` | Integer | Latest Conveyor status |
| `VIZ_ROTARY_STATUS` | Integer | Latest Rotary Turntable status |
| `VIZ_FILLER_A_STATUS` | Integer | Latest Filler A status |
| `VIZ_FILLER_B_STATUS` | Integer | Latest Filler B status |
| `VIZ_LID_STATUS` | Integer | Latest Lid Loader status |
| `VIZ_CAPPER_STATUS` | Integer | Latest Capper status |
| `VIZ_UNLOADER_STATUS` | Integer | Latest Bottle Unloader status |
| `VIZ_REQUIRED_BOTTLES` | Integer | Required bottles in the current product batch |
| `VIZ_COMPLETED_BOTTLES` | Integer | Collected bottles in the current product batch |

Every signal is sent from `CoordinatorCD` to `ABSVisualisationPlantCD` at
`127.0.0.1:11008`.

## Status codes

| Value | Display |
| ---: | --- |
| 0 | IDLE |
| 1 | READY |
| 2 | BUSY |
| 3 | DONE |
| 4 | FAULT |

`WAITING` appears only before the GUI receives a status. It is not code 5.

## Update behaviour

- Coordinator polling remains independent of order processing.
- Each received status updates the stored state and matching `VIZ_*` signal.
- The Visualisation does not poll any Machine Controller.
- Progress starts at `0 / required` and increments only for a Bottle Unloader
  `BOTTLE_DONE` event.
- Multi-product progress resets when the next product batch is dispatched.

## Expected Mock sequence

For Bottle Loader, Conveyor, Rotary Turntable, Filler A, Filler B, Lid Loader,
Capper and Bottle Unloader:

```text
READY -> BUSY -> DONE

Progress: 0 / 2 -> 1 / 2 -> 2 / 2
```

Replacing `MockControllerCD` with real Controllers requires no Visualisation
change when the real Controllers implement the frozen V1 status interface.
