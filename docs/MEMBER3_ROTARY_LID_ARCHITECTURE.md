# Member 3 Rotary Table and Lid Loader Architecture

## Formal diagrams

Editable draw.io source and report-ready exports are stored in
`docs/diagrams/`. The five pages cover the M3 architecture, rotary state
machine, lid-loader state machine, six-position Plant layout, and integration
sequence. See `docs/diagrams/README.md` for captions and export guidance.

## V2.1 design boundary

The frozen interface separates M2 conveyor status from M3 rotary status. M3
therefore implements `RotaryTableControllerCD` directly; it does not expose the
old combined `TransportControllerCD` adapter.

```text
CoordinatorCD
    | ROTARY_STATUS_REQUEST / ROTARY_STATUS (read-only)
    v
RotaryTableControllerCD :11003
    | ROTARY_TABLE_TRIGGER / ROTATION_DONE(cycleId)
    v
RotaryTablePlantCD :12003
    |-- P2 BOTTLE_AT_FILL(full context) -> M4
    |-- P3 BOTTLE_AT_LID_POSITION(bottleId) -> M3 Lid Loader
    |-- P4 BOTTLE_AT_CAP(full context) -> M4
    `-- P6 BOTTLE_AT_LABEL(bottleId) -> M2 labelling/unloader

CoordinatorCD
    | LID_STATUS_REQUEST / LID_STATUS (read-only)
    v
LidLoaderControllerCD :11006
    | PICK_LID_TRIGGER / PLACE_LID_TRIGGER / sensor evidence
    v
LidLoaderPlantCD :12006
```

Status values are `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`, and `4 FAULT`.
Polling never starts, acknowledges or advances a physical operation.

## Rotary control and Plant

```text
READY -- station barrier --> ROTATING -- 500 ms --> VERIFYING_ALIGNMENT
  ^                                                   | aligned
  |                                                   v
  `---------------- acknowledge -------------------- DONE
                                                      |
                                             250 ms timeout
                                                      v
                                                    FAULT
```

The Plant stores P1 load, P2 fill, P3 lid, P4 cap, P5 transfer and P6
label/unload as six independent slots. A physical movement first becomes
pending. Slots shift atomically only when the Controller publishes the same
`cycleId` after sensor-confirmed alignment. The array does not wrap: P6 must be
cleared before the next step, preventing a completed bottle from re-entering
P1.

Rotation is permitted only when every occupied processing station has its
required completion evidence. P6 accepts `P6_CLEAR(bottleId)` only after both a
matching `MARK_LABELLED(bottleId)` and physical-clear report. Wrong, stale and
duplicate bottle identities are rejected.

Alignment timeout de-energises the motor and has no automatic `REHOME` in the
baseline. Reset requires M1 safe-stop acknowledgement, bottle-position
reconciliation and independent position confirmation.

## Lid-loader control and Plant

```text
READY -- bottleId and lid --> PICKING -- picked --> PLACING
  ^                                                   | placed
  `---------------- acknowledge --------------------- DONE
                                                      |
                                            timeout/sensor fault
                                                      v
                                                    FAULT
```

The active `bottleId` is retained through pick, placement and
`LID_CYCLE_DONE(bottleId)`. Both actuators de-energise on fault. Recovery is
cause-specific:

- magazine empty requires restored lid availability;
- pick timeout requires lid availability, actuator home and proof that no lid
  remains held;
- placement timeout or sensor fault requires actuator home, placement
  reconciliation and a healthy placement sensor.

## M4 bottle context

M3 validates and forwards the full proposed context:

```text
bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId
```

The accepted baseline profiles are `S|200|GEOM_S|PACK_S` and
`L|500|GEOM_L|PACK_L`. A context is correlated by exact `bottleId`; malformed
or mismatched data is rejected rather than guessed.

## Traceability and acceptance

| Requirement | Implemented evidence |
| --- | --- |
| Six-position rotary table | six non-wrapping Plant slots |
| 60-degree indexed movement | one committed `cycleId` shifts all slots once |
| Approximately 0.5 s movement | `ROTATION_TIME_MS = 500` |
| Alignment verification | separate verify state and 250 ms timeout |
| Multiple simultaneous bottles | per-slot identity and process state |
| Lid loading | explicit pick/place states and Plant sensors |
| Controller/Plant separation | four SystemJ CDs backed by separate Java models |
| Status visualisation | read-only status interfaces and Swing Plant view |
| Fault handling | safe shutdown and evidence-gated reset |
| Cross-team consistency | exact bottle identity and V2.1 receiver ports |

`Member3ControllerSelfTest` verifies sequencing and recovery evidence.
`Member3PlantSelfTest` verifies six-position flow, multiple bottles, atomic
movement, identity rejection and P6 interlocks. `FaultSupervisorSelfTest`
verifies the separate IP protocol and bounded transfer-recovery policy.
