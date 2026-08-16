# Controller Implementation Guide

## 1. Purpose

This is a practical implementation guide for teammates building the real
Machine Controllers that communicate with Xuqi's M1 Coordinator.

Use `XUQI_M1_TEAM_INTEGRATION_CONTRACT_V1.md` as the frozen M1-facing
contract. This guide mirrors the current interface in:

- `xuqi_coordinator/coordinator.sysj`
- `xuqi_coordinator/coordinator.xml`
- `xuqi_pos/pos.sysj`
- `xuqi_pos/pos.xml`
- `COMPSYS704_Project1_Interface_V1.xlsx`

Use `.sysj` for SystemJ source files. Signal names, data types, Clock Domain
names, directions, and ports in this guide are exact and case-sensitive. Do
not rename them locally.

The `Class="YOUR_GENERATED_CLASS"` value in the XML examples is deliberately
a placeholder: replace it with the Java class generated from your own `.sysj`
Controller. The frozen `ClockDomain Name`, signal names, `To` values, IP, and
ports must not be replaced.

## 2. M1 Coordinator Boundary

```text
CoordinatorCD (127.0.0.1:11001)
  |
  +-- BottleLoaderControllerCD     11002
  +-- ConveyorControllerCD         11003
  +-- FillerAControllerCD          11004
  +-- FillerBControllerCD          11005
  +-- LidLoaderControllerCD        11006
  +-- CapperControllerCD           11007
  +-- ABSVisualisationPlantCD      11008
  +-- RotaryTurntableControllerCD  11009
  +-- BottleUnloaderControllerCD   11010
```

The port in a SystemJ XML mapping is the receiver's port. All current local
integration mappings use IP `127.0.0.1`.

| Receiver | Clock Domain | Port |
|---|---|---:|
| M1 Coordinator | `CoordinatorCD` | `11001` |
| Bottle Loader | `BottleLoaderControllerCD` | `11002` |
| Conveyor | `ConveyorControllerCD` | `11003` |
| Filler A | `FillerAControllerCD` | `11004` |
| Filler B | `FillerBControllerCD` | `11005` |
| Lid Loader | `LidLoaderControllerCD` | `11006` |
| Capper | `CapperControllerCD` | `11007` |
| ABS Visualisation | `ABSVisualisationPlantCD` | `11008` |
| Rotary Turntable | `RotaryTurntableControllerCD` | `11009` |
| Bottle Unloader | `BottleUnloaderControllerCD` | `11010` |

For every Machine Controller:

- Coordinator-to-Controller signals are Controller XML `iSignal` entries
  using `com.systemj.ipc.SimpleServer` on the Controller's receiver port.
- Controller-to-Coordinator signals are Controller XML `oSignal` entries
  using `com.systemj.ipc.SimpleClient`, destination port `11001`, and the exact
  `To="CoordinatorCD.SIGNAL_NAME"` value shown below.
- The Controller owns its Plant interaction and its internal sequencing. M1
  does not send actuator, motor, valve, or sensor commands.

## 3. Common Status Codes

Every `*_STATUS` output is an `Integer signal` using only these values:

| Value | Meaning | Use |
|---:|---|---|
| `0` | `IDLE` | No active work. |
| `1` | `READY` | Ready to accept or perform work. |
| `2` | `BUSY` | Currently performing an operation. |
| `3` | `DONE` | The Controller's operation completed successfully. |
| `4` | `FAULT` | A fault prevents normal operation. |

`WAITING` is only a Visualisation label shown before a status arrives. It is
not a Controller status and there is no status code `5`.

Keep a current status value in the Controller and update it from the
Controller's real operation. Do not calculate a different status just because
the Coordinator requested a reply.

## 4. Common Polling Behaviour

Every `*_STATUS_REQUEST` is a pure signal with no value. It is polling only.
The current Coordinator polls repeatedly, so a Controller must be able to
reply to the same request many times.

Minimum response pattern:

```java
present(YOUR_STATUS_REQUEST) {
    emit YOUR_STATUS(currentStatus);
}
```

Required behaviour:

1. Receive the request.
2. Read the Controller's current status.
3. Emit the matching `Integer` status signal.
4. Continue the Controller's existing operation unchanged.

Receiving a status request must **not** start, restart, continue, complete, or
otherwise advance any physical operation. For example, a poll must not rotate
the table, run a conveyor, open a valve, place a lid, cap a bottle, unload a
bottle, or emit `BOTTLE_DONE`.

## 5. Bottle Loader Controller

Controller name: Bottle Loader Controller<br>
Clock Domain: `BottleLoaderControllerCD`<br>
Receiver port: `11002`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `START_ORDER` | Coordinator → Bottle Loader | `Integer signal` | On presence, read the required bottle quantity for the current product batch. |
| `LOADER_STATUS_REQUEST` | Coordinator → Bottle Loader | pure signal | Reply with the current Loader status; do not load a bottle because of the poll. |
| `LOADER_STATUS` | Bottle Loader → Coordinator | `Integer signal` | Emit in response to each `LOADER_STATUS_REQUEST`. |

`START_ORDER` is quantity-valued and is emitted once per product batch. It is
not one start signal per bottle. After accepting the quantity, the Bottle
Loader owns the internal logic that loads the requested number of bottles.

### SystemJ declarations

```java
input Integer signal START_ORDER;
input signal LOADER_STATUS_REQUEST;
output Integer signal LOADER_STATUS;
```

Typical boundary handling:

```java
present(START_ORDER) {
    int requiredBottleQuantity = #START_ORDER;
    // Pass the quantity to the Loader's internal operation logic.
}

present(LOADER_STATUS_REQUEST) {
    emit LOADER_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="BottleLoaderControllerCD"
             Class="YOUR_GENERATED_CLASS">
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

## 6. Conveyor Controller

Controller name: Conveyor Controller<br>
Clock Domain: `ConveyorControllerCD`<br>
Receiver port: `11003`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `CONVEYOR_STATUS_REQUEST` | Coordinator → Conveyor | pure signal | Reply with the current Conveyor status without changing conveyor motion. |
| `CONVEYOR_STATUS` | Conveyor → Coordinator | `Integer signal` | Emit in response to each `CONVEYOR_STATUS_REQUEST`. |

### SystemJ declarations and polling

```java
input signal CONVEYOR_STATUS_REQUEST;
output Integer signal CONVEYOR_STATUS;

present(CONVEYOR_STATUS_REQUEST) {
    emit CONVEYOR_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="ConveyorControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="CONVEYOR_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11003" />
    <oSignal Name="CONVEYOR_STATUS"
             To="CoordinatorCD.CONVEYOR_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

Do not use the obsolete combined Transport names. Conveyor uses only
`CONVEYOR_STATUS_REQUEST` and `CONVEYOR_STATUS` at the M1 boundary.

## 7. Rotary Turntable Controller

Controller name: Rotary Turntable Controller<br>
Clock Domain: `RotaryTurntableControllerCD`<br>
Receiver port: `11009`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `ROTARY_STATUS_REQUEST` | Coordinator → Rotary Turntable | pure signal | Reply with the current Rotary status without rotating or advancing the table. |
| `ROTARY_STATUS` | Rotary Turntable → Coordinator | `Integer signal` | Emit in response to each `ROTARY_STATUS_REQUEST`. |

### SystemJ declarations and polling

```java
input signal ROTARY_STATUS_REQUEST;
output Integer signal ROTARY_STATUS;

present(ROTARY_STATUS_REQUEST) {
    emit ROTARY_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="RotaryTurntableControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="ROTARY_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11009" />
    <oSignal Name="ROTARY_STATUS"
             To="CoordinatorCD.ROTARY_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

Conveyor and Rotary Turntable are separate Controllers and separate Clock
Domains. Do not recreate a combined `TransportControllerCD`.

## 8. Filler A Controller

Controller name: Filler A Controller<br>
Clock Domain: `FillerAControllerCD`<br>
Receiver port: `11004`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `FILL_A_RATIO` | Coordinator → Filler A | `Integer signal` | On presence, store the required Liquid A percentage for the current product batch. |
| `FILLER_A_STATUS_REQUEST` | Coordinator → Filler A | pure signal | Reply with the current Filler A status; do not operate a valve because of the poll. |
| `FILLER_A_STATUS` | Filler A → Coordinator | `Integer signal` | Emit in response to each `FILLER_A_STATUS_REQUEST`. |

`FILL_A_RATIO` carries the current product's Liquid A percentage as an integer
from `0` to `100`. It is recipe data, not a per-bottle fill command and not a
direct Plant valve command.

### SystemJ declarations

```java
input Integer signal FILL_A_RATIO;
input signal FILLER_A_STATUS_REQUEST;
output Integer signal FILLER_A_STATUS;
```

Typical boundary handling:

```java
present(FILL_A_RATIO) {
    int currentLiquidAPercentage = #FILL_A_RATIO;
    // Store the percentage for the Filler's internal operation logic.
}

present(FILLER_A_STATUS_REQUEST) {
    emit FILLER_A_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="FillerAControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="FILL_A_RATIO"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11004" />
    <iSignal Name="FILLER_A_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11004" />
    <oSignal Name="FILLER_A_STATUS"
             To="CoordinatorCD.FILLER_A_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

## 9. Filler B Controller

Controller name: Filler B Controller<br>
Clock Domain: `FillerBControllerCD`<br>
Receiver port: `11005`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `FILL_B_RATIO` | Coordinator → Filler B | `Integer signal` | On presence, store the required Liquid B percentage for the current product batch. |
| `FILLER_B_STATUS_REQUEST` | Coordinator → Filler B | pure signal | Reply with the current Filler B status; do not operate a valve because of the poll. |
| `FILLER_B_STATUS` | Filler B → Coordinator | `Integer signal` | Emit in response to each `FILLER_B_STATUS_REQUEST`. |

`FILL_B_RATIO` carries the current product's Liquid B percentage as an integer
from `0` to `100`. It is recipe data, not a per-bottle fill command and not a
direct Plant valve command.

### SystemJ declarations

```java
input Integer signal FILL_B_RATIO;
input signal FILLER_B_STATUS_REQUEST;
output Integer signal FILLER_B_STATUS;
```

Typical boundary handling:

```java
present(FILL_B_RATIO) {
    int currentLiquidBPercentage = #FILL_B_RATIO;
    // Store the percentage for the Filler's internal operation logic.
}

present(FILLER_B_STATUS_REQUEST) {
    emit FILLER_B_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="FillerBControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="FILL_B_RATIO"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11005" />
    <iSignal Name="FILLER_B_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11005" />
    <oSignal Name="FILLER_B_STATUS"
             To="CoordinatorCD.FILLER_B_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

## 10. Lid Loader Controller

Controller name: Lid Loader Controller<br>
Clock Domain: `LidLoaderControllerCD`<br>
Receiver port: `11006`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `LID_STATUS_REQUEST` | Coordinator → Lid Loader | pure signal | Reply with the current Lid Loader status without placing a lid. |
| `LID_STATUS` | Lid Loader → Coordinator | `Integer signal` | Emit in response to each `LID_STATUS_REQUEST`. |

### SystemJ declarations and polling

```java
input signal LID_STATUS_REQUEST;
output Integer signal LID_STATUS;

present(LID_STATUS_REQUEST) {
    emit LID_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="LidLoaderControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="LID_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11006" />
    <oSignal Name="LID_STATUS"
             To="CoordinatorCD.LID_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

The exact current M1 names are `LID_STATUS_REQUEST` and `LID_STATUS`.

## 11. Capper Controller

Controller name: Capper Controller<br>
Clock Domain: `CapperControllerCD`<br>
Receiver port: `11007`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `CAPPER_STATUS_REQUEST` | Coordinator → Capper | pure signal | Reply with the current Capper status without starting or advancing capping. |
| `CAPPER_STATUS` | Capper → Coordinator | `Integer signal` | Emit in response to each `CAPPER_STATUS_REQUEST`. |

### SystemJ declarations and polling

```java
input signal CAPPER_STATUS_REQUEST;
output Integer signal CAPPER_STATUS;

present(CAPPER_STATUS_REQUEST) {
    emit CAPPER_STATUS(currentStatus);
}
```

### Controller XML mapping

```xml
<ClockDomain Name="CapperControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="CAPPER_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11007" />
    <oSignal Name="CAPPER_STATUS"
             To="CoordinatorCD.CAPPER_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

Capper completion is not the M1 order-completion event. The Capper must not
emit `BOTTLE_DONE`.

## 12. Bottle Unloader Controller

Controller name: Bottle Unloader Controller<br>
Clock Domain: `BottleUnloaderControllerCD`<br>
Receiver port: `11010`

### Interface

| Signal | Direction | Type | When to handle or emit |
|---|---|---|---|
| `UNLOADER_STATUS_REQUEST` | Coordinator → Bottle Unloader | pure signal | Reply with the current Unloader status without starting or advancing unloading. |
| `UNLOADER_STATUS` | Bottle Unloader → Coordinator | `Integer signal` | Emit in response to each `UNLOADER_STATUS_REQUEST`. |
| `BOTTLE_DONE` | Bottle Unloader → Coordinator | pure signal | Emit exactly once after one completed bottle is successfully collected/unloaded. |

One `BOTTLE_DONE` means one completed bottle has successfully reached and been
collected at the Bottle Unloader. It must be emitted exactly once per completed
bottle. Do not emit it when capping finishes, when a status poll arrives, or
when a bottle merely leaves an upstream station.

### SystemJ declarations

```java
input signal UNLOADER_STATUS_REQUEST;
output Integer signal UNLOADER_STATUS;
output signal BOTTLE_DONE;
```

Typical boundary handling:

```java
present(UNLOADER_STATUS_REQUEST) {
    emit UNLOADER_STATUS(currentStatus);
}

// In the Unloader's real successful-collection completion branch only:
emit BOTTLE_DONE;
```

### Controller XML mapping

```xml
<ClockDomain Name="BottleUnloaderControllerCD"
             Class="YOUR_GENERATED_CLASS">
    <iSignal Name="UNLOADER_STATUS_REQUEST"
             Class="com.systemj.ipc.SimpleServer"
             IP="127.0.0.1" Port="11010" />
    <oSignal Name="UNLOADER_STATUS"
             To="CoordinatorCD.UNLOADER_STATUS"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
    <oSignal Name="BOTTLE_DONE"
             To="CoordinatorCD.BOTTLE_DONE"
             Class="com.systemj.ipc.SimpleClient"
             IP="127.0.0.1" Port="11001" />
</ClockDomain>
```

## 13. What Machine Controllers Must NOT Depend On

Machine Controllers must not depend on M1 to control physical steps. In
particular:

- Do not expose Controller-to-Plant actuator or sensor signals to
  `CoordinatorCD`.
- Do not expect M1 to turn motors on/off, open/close valves, rotate the table,
  place lids, cap bottles, or operate the Unloader.
- Do not expose Controller-to-Controller pipeline signals through the M1
  interface. Those signals are owned by the relevant machine implementations.
- Do not use POS signals such as `ORDER` or `ORDER_COMPLETE` in a Machine
  Controller. POS communicates with `CoordinatorCD`.
- Do not use Visualisation `VIZ_*` signals in a Machine Controller.
- Do not use `WAITING` as a status code.
- Do not use obsolete `TRANSPORT_STATUS_REQUEST`, `TRANSPORT_STATUS`, or
  `TransportControllerCD` names.
- Do not make a physical operation depend on receiving a status poll.
- Do not emit `BOTTLE_DONE` from the Capper or any Controller other than
  `BottleUnloaderControllerCD`.

## 14. Minimum Controller Integration Test

Run the Controller with its real Clock Domain name and assigned receiver port,
then connect it to `CoordinatorCD` at `127.0.0.1:11001`.

For every Controller, verify:

1. The Controller starts and reaches status `READY` (`1`).
2. The Coordinator sends the Controller's exact `*_STATUS_REQUEST`.
3. The Controller replies with `READY` without starting, restarting, or
   advancing a physical operation.
4. Repeated status requests produce repeated current-status replies and no
   physical side effects.
5. During a real operation, the Controller reports `BUSY` (`2`).
6. After successful completion, the Controller reports `DONE` (`3`) or its
   appropriate current state.
7. When a real fault applies, the Controller reports `FAULT` (`4`).
8. Every reply reaches the exact `CoordinatorCD.*_STATUS` destination on port
   `11001`.

Additional Controller-specific checks:

- Bottle Loader: send a quantity-valued `START_ORDER` and confirm the quantity
  is accepted once for the product batch, without requiring a per-bottle M1
  start signal.
- Filler A/B: send the current `FILL_A_RATIO` / `FILL_B_RATIO` and confirm the
  recipe values are stored without directly operating Plant valves.
- Bottle Unloader: complete one real collection and confirm exactly one
  `BOTTLE_DONE` reaches `CoordinatorCD.BOTTLE_DONE`; confirm capping alone and
  status polling emit no `BOTTLE_DONE`.

## 15. Integration Checklist

- [ ] Signal names match `XUQI_M1_TEAM_INTEGRATION_CONTRACT_V1.md` exactly.
- [ ] Signal directions match the contract.
- [ ] Pure signals and `Integer signal` types match.
- [ ] The Controller Clock Domain name matches exactly.
- [ ] The Controller receiver port matches the current assignment.
- [ ] Controller outputs use `127.0.0.1:11001` as the Coordinator receiver.
- [ ] Every Controller XML `To` value is exactly
      `CoordinatorCD.SIGNAL_NAME` for that output.
- [ ] Every `*_STATUS_REQUEST` is polling only and has no physical side
      effects.
- [ ] Repeated polling is safe.
- [ ] Status codes are limited to `0` through `4`.
- [ ] `WAITING` is not emitted as a Controller status.
- [ ] No Plant actuator or sensor signals are exposed to `CoordinatorCD`.
- [ ] Controller-to-Controller internal signals remain outside the M1
      interface.
- [ ] Conveyor and Rotary Turntable are separate Controllers and Clock
      Domains.
- [ ] `START_ORDER` is treated as a product-batch bottle quantity.
- [ ] `FILL_A_RATIO` and `FILL_B_RATIO` are treated as recipe percentages.
- [ ] Only `BottleUnloaderControllerCD` emits `BOTTLE_DONE`.
- [ ] One successful collected bottle produces exactly one `BOTTLE_DONE`.
