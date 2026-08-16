# Member 3 Rotary Table and Lid Loader Architecture

## Design decision

Interface Freeze V1 exposes a combined `TransportControllerCD` boundary to the
Coordinator. The project brief separately requires a conveyor clock-domain and
a rotary-table clock-domain. Both constraints are satisfied by treating
`TransportControllerCD` as the stable external adapter and keeping the rotary
rotary behaviour as an independent Controller/Plant pair behind that boundary.
The conveyor remains owned by Member 2.

```text
CoordinatorCD
    |
    | TRANSPORT_STATUS_REQUEST / TRANSPORT_STATUS (frozen)
    v
TransportControllerCD (external status adapter)
    |
    +-- ConveyorControllerCD (Member 2)
    `-- Rotary control       (Member 3)
            |
            `-- RotaryTablePlantCD (implemented)

CoordinatorCD
    |
    | LID_STATUS_REQUEST / LID_STATUS (frozen)
    v
LidLoaderControllerCD (Member 3)
    |
    `-- LidLoaderPlantCD (implemented)
```

The adapter reports the most severe internal state: `FAULT` first, then
`BUSY`, `DONE`, `READY`, and `IDLE`. It does not issue actuator commands.

## Coordinator boundary

The following interface is frozen and must not be changed locally.

| Clock-domain | Input | Output | Receiver port |
| --- | --- | --- | ---: |
| `TransportControllerCD` | `TRANSPORT_STATUS_REQUEST` | `TRANSPORT_STATUS: Integer` | 11003 |
| `LidLoaderControllerCD` | `LID_STATUS_REQUEST` | `LID_STATUS: Integer` | 11006 |

Status values are `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`, and `4 FAULT`.
Polling reads state only; it must never trigger or advance an operation.

## Rotary controller

The controller performs one indexed step at a time.

```text
READY
  | rotate request and exit clear
  v
ROTATING -- 500 ms --> VERIFYING_ALIGNMENT
                            | aligned
                            v
                           DONE -- acknowledge --> READY
                            |
                            | 250 ms timeout
                            v
                           FAULT -- aligned reset --> READY
```

Local inputs proposed from neighbouring controllers or the plant:

- rotate request;
- `capOnBottleAtPos1` interlock;
- `tableAlignedWithSensor` confirmation;
- elapsed simulation time;
- done acknowledgement and fault reset.

Local output: `rotaryTableTrigger`, sustained only while `ROTATING`.

Safety invariants:

- the motor is off in `READY`, `VERIFYING_ALIGNMENT`, `DONE`, and `FAULT`;
- a capped bottle at position 1 blocks a new rotation;
- a step is not complete until the alignment sensor is present;
- a missing alignment confirmation produces `FAULT`, not `DONE`;
- one successful operation advances exactly one of six table positions.

## Lid-loader controller

```text
READY -- bottle and lid --> PICKING -- picked --> PLACING
  |                                      | placed
  | no lid                               v
  `-----------------> FAULT             DONE -- acknowledge --> READY
                         ^
                         `-- pick/place timeout
```

Local inputs proposed from the Lab 3 plant interface:

- bottle present at the lid-placement position;
- lid available in the magazine;
- lid picked confirmation;
- lid placed confirmation;
- elapsed simulation time, acknowledgement, and reset.

The pick and place actuators are de-energised immediately in `DONE` or
`FAULT`. A no-lid fault can reset only after the magazine is replenished.

## Controller/plant separation

`RotaryControllerModelV1` and `LidLoaderControllerModelV1` contain decisions
and sequencing. The implemented Plant clock-domains own physical state, sensor
generation, actuator delay, six bottle positions, lid inventory, and GUI
updates. This keeps
the controller deployable without changing its logic, as required by the
brief.

## Traceability to the brief

| Requirement | Design evidence |
| --- | --- |
| Six-position table | position index wraps from 5 to 0 |
| 60-degree indexed movement | one request advances exactly one position |
| Approximately 0.5 s rotation | `ROTATION_TIME_MS = 500` |
| Verify table alignment | `VERIFYING_ALIGNMENT` state and timeout |
| Avoid blocked exit | capped-bottle interlock at position 1 |
| Lid supplied and placed | explicit `PICKING` and `PLACING` states |
| Controller plus plant | separate Controller and implemented Plant CDs |
| Status visualisation | frozen status responses, plus plant GUI ownership |
| Fault handling | alignment, empty-magazine, pick, and place faults |
| Multiple simultaneous bottles | six plant slots must move atomically per step |

## Integration acceptance tests

1. Repeated status polling leaves an idle machine in `READY`.
2. Rotary motor remains on for 500 ms and stops before alignment checking.
3. Alignment confirmation completes one 60-degree step.
4. Missing alignment causes `FAULT` and motor shutdown.
5. A capped bottle at position 1 prevents rotation.
6. Lid loading cannot start without a bottle.
7. An empty magazine causes `FAULT`.
8. Missing pick or placement confirmation causes `FAULT` and actuator shutdown.
9. Fault reset is rejected while its physical cause remains.
10. Coordinator receives only frozen status codes and no local plant signal.

The framework-free `Member3ControllerSelfTest` covers tests 1-9 at the model
level. Final SystemJ integration must repeat them with the real Plant CDs and
also demonstrate bottle-position visualisation.
