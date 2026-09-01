# Member 3 Team Integration Contract V1

**Status:** Frozen baseline

**Owner:** Member 3

**Applies to:** M1 Coordinator, M2 Loader/Conveyor/Unloader, M4 Filler/Capper

**Local integration IP:** `127.0.0.1`

This is the implementation baseline for all signals entering Member 3
clock-domains. Members may copy the declarations and XML mappings below
directly. For distributed deployment, replace only `127.0.0.1` with the
receiver machine IP. Do not rename signals or change their types or ports.

## Frozen M3 receivers

| Receiver clock-domain | Owner | Port |
| --- | --- | ---: |
| `TransportControllerCD` | M3 | 11003 |
| `RotaryTablePlantCD` | M3 | 12003 |
| `LidLoaderControllerCD` | M3 | 11006 |
| `LidLoaderPlantCD` | M3 | 12006 |

M1, M2 and M4 must not declare these ports as local receiver ports.

## M1 Coordinator contract

### SystemJ declarations

```java
output signal TRANSPORT_STATUS_REQUEST;
input Integer signal TRANSPORT_STATUS;

output signal LID_STATUS_REQUEST;
input Integer signal LID_STATUS;
```

### M1 XML mappings

Add these entries inside `CoordinatorCD`. M1 owns receiver port `11001`.

```xml
<oSignal Name="TRANSPORT_STATUS_REQUEST"
    To="TransportControllerCD.TRANSPORT_STATUS_REQUEST"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="11003" />

<iSignal Name="TRANSPORT_STATUS"
    Class="com.systemj.ipc.SimpleServer"
    IP="127.0.0.1" Port="11001" />

<oSignal Name="LID_STATUS_REQUEST"
    To="LidLoaderControllerCD.LID_STATUS_REQUEST"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="11006" />

<iSignal Name="LID_STATUS"
    Class="com.systemj.ipc.SimpleServer"
    IP="127.0.0.1" Port="11001" />
```

M3 returns an `Integer`: `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`, `4 FAULT`.
Polling must not start or advance an operation.

## M2 Loader/Conveyor/Unloader contract

### SystemJ declarations

```java
output String signal LOAD_BOTTLE;
output signal REMOVE_BOTTLE;
```

### Required emissions

```java
// Emit once after the bottle is committed to rotary-table position 1.
emit LOAD_BOTTLE(bottleId);

// Emit once after the unloader accepts the bottle at position 5.
emit REMOVE_BOTTLE;
```

`bottleId` must be non-empty and unique within the order. M2 must not resend
`LOAD_BOTTLE` while position 1 remains occupied.

### M2 XML mappings

Add these entries inside the M2 clock-domain that declares the outputs.

```xml
<oSignal Name="LOAD_BOTTLE"
    To="RotaryTablePlantCD.LOAD_BOTTLE"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="12003" />

<oSignal Name="REMOVE_BOTTLE"
    To="RotaryTablePlantCD.REMOVE_BOTTLE"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="12003" />
```

M2 does not emit `ROTATE_REQUEST`; rotation authority belongs to M3.

## M4 Filler/Capper contract

### SystemJ declarations

```java
output signal MARK_FILLED;
output signal MARK_CAPPED;
```

### Required emissions

```java
// Emit once after the position-2 bottle reaches its required fill quantity.
emit MARK_FILLED;

// Emit once after cap tightening succeeds at position 4.
emit MARK_CAPPED;
```

Do not emit either completion signal on a timeout, actuator failure, empty
bottle position, or aborted operation.

### M4 XML mappings

Add each mapping inside the M4 clock-domain that declares that output.

```xml
<oSignal Name="MARK_FILLED"
    To="RotaryTablePlantCD.MARK_FILLED"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="12003" />

<oSignal Name="MARK_CAPPED"
    To="RotaryTablePlantCD.MARK_CAPPED"
    Class="com.systemj.ipc.SimpleClient"
    IP="127.0.0.1" Port="12003" />
```

M4 does not emit `ROTATE_REQUEST`; rotation authority belongs to M3.

## Signals that other members must not implement

The following signals are internal to M3:

- `ROTATE_REQUEST`
- `ROTATION_DONE`
- `ROTARY_TABLE_TRIGGER`
- `TABLE_ALIGNED_WITH_SENSOR`
- `CAP_ON_BOTTLE_AT_POS1`
- `BOTTLE_AT_LID_POSITION`
- `LID_POSITION_CLEAR`
- `PICK_LID_TRIGGER`
- `PLACE_LID_TRIGGER`
- `LID_ABORT`
- `LID_AVAILABLE`
- `LID_MAGAZINE_EMPTY`
- `LID_PICKED`
- `LID_PLACED_SENSOR`
- `LID_CYCLE_DONE`
- `MARK_LID_PLACED`

## Event delivery rules

1. `LOAD_BOTTLE` is a valued `String` signal; all other cross-member signals
   in this contract are pure signals.
2. Each event is emitted exactly once for one physical completion.
3. Senders wait until the physical operation succeeds; starting an actuator
   is not completion.
4. No member writes directly to another member's Java state.
5. Repeated status requests are allowed and have no side effects.
6. Any contract change creates V2 and must be made before branch integration.

## Minimum integration test

The merged system must demonstrate this order:

```text
M2 LOAD_BOTTLE
-> M3 indexed rotation
-> M4 MARK_FILLED
-> M3 indexed rotation and lid cycle
-> M4 MARK_CAPPED
-> M3 indexed rotation
-> M2 REMOVE_BOTTLE
```

During the scenario, M1 must observe valid transport and lid status codes.

## Separate group decision

M1's current diagram sends `BOTTLE_DONE` from Capper to Coordinator. That
signal does not enter M3 and therefore is outside this M3 receiver contract.
The group must still decide whether it means "capping completed" or "bottle
successfully unloaded" so Coordinator does not count an order too early.
