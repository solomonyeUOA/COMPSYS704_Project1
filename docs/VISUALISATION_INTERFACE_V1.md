# ABS Visualisation Interface V1

This document defines the display-only interface from `CoordinatorCD` to the
Overall ABS Visualisation Plant.

It is separate from `INTERFACE_FREEZE_V1.md`: none of the frozen
Coordinator-to-Controller signals, status codes, Clock Domains or ports
11000-11007 are changed.

## Architecture boundary

```text
Machine Controllers / test Mock
              |
              | frozen STATUS signals
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

`ABSVisualisationPlantCD` does not connect to Machine Controllers. It receives
the latest state already stored by the Coordinator, and it contains no Plant
actuator or control sequence.

## Clock Domain and receiver mapping

```text
Clock Domain: ABSVisualisationPlantCD
Class:        ABSVisualisationPlant
IP:           127.0.0.1
Port:         11008
```

All signals use the Lab 3 `SimpleClient` / `SimpleServer` XML pattern. Port
11008 belongs to the receiving Visualisation runtime.

## Coordinator outputs

| Signal | Type | Meaning |
| --- | --- | --- |
| `VIZ_LOADER_STATUS` | Integer | Latest Bottle Loader status |
| `VIZ_TRANSPORT_STATUS` | Integer | Latest Conveyor/Turntable status |
| `VIZ_FILLER_A_STATUS` | Integer | Latest Filler A status |
| `VIZ_FILLER_B_STATUS` | Integer | Latest Filler B status |
| `VIZ_LID_STATUS` | Integer | Latest Lid Loader status |
| `VIZ_CAPPER_STATUS` | Integer | Latest Capper status |
| `VIZ_REQUIRED_BOTTLES` | Integer | Required bottles in the current product batch |
| `VIZ_COMPLETED_BOTTLES` | Integer | Completed bottles in the current product batch |

## Status codes

The visualisation uses the existing frozen status codes without translation at
the interface boundary:

| Value | Display |
| ---: | --- |
| 0 | IDLE |
| 1 | READY |
| 2 | BUSY |
| 3 | DONE |
| 4 | FAULT |

## Update behaviour

- The Coordinator continues its existing Controller status polling.
- Each received Controller status updates the Coordinator's stored state.
- The stored value is emitted on the corresponding `VIZ_*_STATUS` signal.
- The Visualisation does not send a second request to any Controller.
- Progress starts at `0 / required`, increments for each `BOTTLE_DONE`, and
  remains at `required / required` when the final bottle completes.
- For a multi-product order, progress describes the current product batch and
  resets to zero when the next batch is dispatched.

The Java class `visualisation/ABSVisualisation.java` is handwritten Swing view
support. It opens no network connection; all communication remains in
`ABSVisualisationPlantCD`.

## Expected Mock sequence

```text
READY -> BUSY -> DONE

Progress: 0 / 2 -> 1 / 2 -> 2 / 2
```

Replacing `MockControllerCD` with real Controllers does not require any
Visualisation change, provided the real Controllers implement the frozen V1
status interface.
