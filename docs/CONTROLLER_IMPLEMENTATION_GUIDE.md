# Controller Implementation Guide

This guide is for team members implementing Project 1 Machine Controllers.
Read `INTERFACE_FREEZE_V1.md` first. Signal names, types, status codes, Clock
Domain names and ports are frozen for Integration V1.

## Common rules

1. Use `.sysj`, not `.syj`.
2. A status request is a normal pure SystemJ input signal with no value.
3. A status response is an `Integer signal` using only codes 0-4.
4. Send status responses to `CoordinatorCD` at `127.0.0.1:11001`.
5. Keep all valve, motor, actuator and sensor logic inside your Controller and
   Plant modules.
6. Coordinate bottle/workpiece movement directly with neighbouring Controllers;
   that protocol is outside the Coordinator V1 interface.
7. Do not rename a frozen signal or Clock Domain without group agreement.

## Status codes

```text
0 IDLE
1 READY
2 BUSY
3 DONE
4 FAULT
```

When your Controller receives its status request, emit its latest status value:

```text
present(YOUR_STATUS_REQUEST) {
    emit YOUR_STATUS(currentStatus);
}
```

## Bottle Loader Controller

Frozen Clock Domain: `BottleLoaderControllerCD`  
Input port: `11002`

Receive:

```text
START_ORDER          : Integer
LOADER_STATUS_REQUEST: pure signal
```

Send:

```text
LOADER_STATUS        : Integer
```

`START_ORDER` is emitted once per product batch. The Loader must load that many
bottles without requiring one Coordinator START event per bottle.

Suggested declaration shape:

```text
BottleLoaderController(
    input Integer signal START_ORDER;
    input signal LOADER_STATUS_REQUEST;
    output Integer signal LOADER_STATUS;
)
```

## Conveyor / Turntable Controller

Frozen Clock Domain: `TransportControllerCD`  
Input port: `11003`

Receive:

```text
TRANSPORT_STATUS_REQUEST: pure signal
```

Send:

```text
TRANSPORT_STATUS        : Integer
```

## Filler A Controller

Frozen Clock Domain: `FillerAControllerCD`  
Input port: `11004`

Receive:

```text
FILL_A_RATIO           : Integer, 0-100
FILLER_A_STATUS_REQUEST: pure signal
```

Send:

```text
FILLER_A_STATUS        : Integer
```

Store the latest ratio and apply it when bottles arrive at Filler A. The
Coordinator does not issue per-bottle fill commands.

## Filler B Controller

Frozen Clock Domain: `FillerBControllerCD`  
Input port: `11005`

Receive:

```text
FILL_B_RATIO           : Integer, 0-100
FILLER_B_STATUS_REQUEST: pure signal
```

Send:

```text
FILLER_B_STATUS        : Integer
```

Store the latest ratio and apply it when bottles arrive at Filler B. Filler A
and B ratios will sum to 100 for a valid order.

## Lid Loader Controller

Frozen Clock Domain: `LidLoaderControllerCD`  
Input port: `11006`

Receive:

```text
LID_STATUS_REQUEST: pure signal
```

Send:

```text
LID_STATUS        : Integer
```

The Lab 3 Lid/Cap Loader is a useful Controller/Plant separation reference, but
its internal actuator signals are not Coordinator signals.

## Capper / Final Controller

Frozen Clock Domain: `CapperControllerCD`  
Input port: `11007`

Receive:

```text
CAPPER_STATUS_REQUEST: pure signal
```

Send:

```text
CAPPER_STATUS        : Integer
BOTTLE_DONE          : pure signal
```

Emit exactly one `BOTTLE_DONE` event after each bottle has completed the whole
production line. Do not emit one event for an internal Capper sub-step.

## XML pattern

Follow the Lab 3 `SimpleServer` / `SimpleClient` pattern. For example, the
Bottle Loader's Coordinator-facing mappings are:

```xml
<ClockDomain Name="BottleLoaderControllerCD" Class="BottleLoaderController">
    <iSignal Name="START_ORDER"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11002" />
    <iSignal Name="LOADER_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11002" />

    <oSignal Name="LOADER_STATUS"
             To="CoordinatorCD.LOADER_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

Use the exact receiver Clock Domain and port listed for your machine in
`INTERFACE_FREEZE_V1.md`.
