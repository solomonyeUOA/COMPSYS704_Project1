# Xuqi M1 Team Integration Contract V1

Status: Frozen integration baseline<br>
Owner: Xuqi (M1)<br>
Modules: POS + Coordinator<br>
Scope: M1-facing integration only<br>
Local integration IP: 127.0.0.1

## 1. Purpose and authority

This contract defines the exact boundary that other team members must use to
connect their Machine Controllers to Xuqi's current M1 POS and ABS
Coordinator.

The following current files are the source of truth for this contract:

- `xuqi_coordinator/coordinator.sysj`
- `xuqi_coordinator/coordinator.xml`
- `xuqi_pos/pos.sysj`
- `xuqi_pos/pos.xml`
- `COMPSYS704_Project1_Interface_V1.xlsx`

The legacy `pos/` and `coordinator/` folders are not part of this contract.
Controller-to-Controller signals, Controller-to-Plant sensors/actuators, and
the internal implementations of M2/M3/M4 are outside scope.

The five source-of-truth files were checked before this contract was written.
Their Coordinator-facing signal names, SystemJ types, receiver Clock Domains,
and receiver ports are consistent. Any future change to a frozen name, type,
Clock Domain, port, or payload requires team agreement and a new contract
version.

## 2. Current M1-facing architecture

```text
POSCD
  |
  | ORDER / ORDER_COMPLETE
  v
CoordinatorCD
  |
  +-- BottleLoaderControllerCD
  +-- ConveyorControllerCD
  +-- RotaryTurntableControllerCD
  +-- FillerAControllerCD
  +-- FillerBControllerCD
  +-- LidLoaderControllerCD
  +-- CapperControllerCD
  +-- BottleUnloaderControllerCD
  |
  +--> ABSVisualisationPlantCD
```

The Coordinator performs order, recipe, status, progress, and completion
coordination only. It does not send Plant actuator commands and it does not
define the internal movement of a bottle between Machine Controllers.

## 3. Receiver Clock Domains and ports

The port in each XML mapping belongs to the receiving SystemJ runtime.

| Receiver component | Receiver Clock Domain | Receiver port |
|---|---|---:|
| POS | `POSCD` | `11000` |
| ABS Coordinator | `CoordinatorCD` | `11001` |
| Bottle Loader Controller | `BottleLoaderControllerCD` | `11002` |
| Conveyor Controller | `ConveyorControllerCD` | `11003` |
| Filler A Controller | `FillerAControllerCD` | `11004` |
| Filler B Controller | `FillerBControllerCD` | `11005` |
| Lid Loader Controller | `LidLoaderControllerCD` | `11006` |
| Capper Controller | `CapperControllerCD` | `11007` |
| ABS Visualisation | `ABSVisualisationPlantCD` | `11008` |
| Rotary Turntable Controller | `RotaryTurntableControllerCD` | `11009` |
| Bottle Unloader Controller | `BottleUnloaderControllerCD` | `11010` |

All current local mappings use `127.0.0.1`.

## 4. Shared Controller status convention

Every Machine Controller status reply is an `Integer` with the following
meaning:

| Value | Meaning |
|---:|---|
| `0` | `IDLE` |
| `1` | `READY` |
| `2` | `BUSY` |
| `3` | `DONE` |
| `4` | `FAULT` |

`WAITING` is only an initial Visualisation label. It is not a Controller
status value and there is no status code `5`.

Every `*_STATUS_REQUEST` in this contract is a pure polling event. The current
Coordinator emits these requests independently, approximately once per 1000
milliseconds. On receipt, a Controller must report its current status through
the matching `*_STATUS` signal.

**A `*_STATUS_REQUEST` must not start, restart, continue, or advance a physical
operation.** Polling must be side-effect free apart from sending the status
reply.

## 5. POSCD ↔ CoordinatorCD

### 5.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `ORDER` | POS → Coordinator | `String` | `CoordinatorCD` | `11001` | Submit one validated order payload. |
| `ORDER_COMPLETE` | Coordinator → POS | `String` | `POSCD` | `11000` | Report completion of the full order. |

### 5.2 Exact payload formats

`ORDER`:

```text
orderId|productCount|productId,A%,B%,quantity;...
```

The third field contains `productCount` product records. Product records are
separated by `;`. Each record has exactly these comma-separated fields:

```text
productId,A%,B%,quantity
```

`A%` and `B%` are the integer recipe percentages for that product, and
`quantity` is the number of bottles in that product batch. The POS validates
the payload before sending it, and the Coordinator performs its own validation
after receiving it.

`ORDER_COMPLETE`:

```text
orderId|COMPLETED|completionTimeSeconds
```

### 5.3 Required SystemJ declarations

Current `POSCD` side:

```java
input String signal ORDER_COMPLETE;
output String signal ORDER;
```

Current `CoordinatorCD` side:

```java
input String signal ORDER;
output String signal ORDER_COMPLETE;
```

### 5.4 Current XML mappings

POS side:

| Signal | POS XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `ORDER` | `oSignal` | `CoordinatorCD.ORDER` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11001` |
| `ORDER_COMPLETE` | `iSignal` | `POSCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11000` |

Coordinator side:

| Signal | Coordinator XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `ORDER` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |
| `ORDER_COMPLETE` | `oSignal` | `POSCD.ORDER_COMPLETE` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11000` |

## 6. CoordinatorCD ↔ BottleLoaderControllerCD

### 6.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `START_ORDER` | Coordinator → Bottle Loader | `Integer` | `BottleLoaderControllerCD` | `11002` | Start the current product batch with the supplied bottle quantity. |
| `LOADER_STATUS_REQUEST` | Coordinator → Bottle Loader | pure signal | `BottleLoaderControllerCD` | `11002` | Request the current Loader status. |
| `LOADER_STATUS` | Bottle Loader → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Loader status code. |

`START_ORDER` is emitted once per product batch and carries the batch
`quantity`. It is not a per-bottle start pulse. The Bottle Loader owns its
internal logic for loading the requested number of bottles.

### 6.2 Required SystemJ declarations

Current Coordinator side:

```java
output Integer signal START_ORDER;
output signal LOADER_STATUS_REQUEST;
input Integer signal LOADER_STATUS;
```

Required Bottle Loader side:

```java
input Integer signal START_ORDER;
input signal LOADER_STATUS_REQUEST;
output Integer signal LOADER_STATUS;
```

### 6.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `START_ORDER` | `oSignal` | `BottleLoaderControllerCD.START_ORDER` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11002` |
| `LOADER_STATUS_REQUEST` | `oSignal` | `BottleLoaderControllerCD.LOADER_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11002` |
| `LOADER_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 6.4 What the Bottle Loader owner must implement

- Use the exact Clock Domain name `BottleLoaderControllerCD`.
- Receive `START_ORDER` and `LOADER_STATUS_REQUEST` on port `11002`.
- Interpret `#START_ORDER` as the number of bottles in the current product
  batch.
- Reply to each status poll with `LOADER_STATUS` sent to
  `CoordinatorCD.LOADER_STATUS` on port `11001`.
- Keep polling separate from the Loader's physical operation.

## 7. CoordinatorCD ↔ ConveyorControllerCD

### 7.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `CONVEYOR_STATUS_REQUEST` | Coordinator → Conveyor | pure signal | `ConveyorControllerCD` | `11003` | Request the current Conveyor status. |
| `CONVEYOR_STATUS` | Conveyor → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Conveyor status code. |

### 7.2 Required SystemJ declarations

Current Coordinator side:

```java
output signal CONVEYOR_STATUS_REQUEST;
input Integer signal CONVEYOR_STATUS;
```

Required Conveyor side:

```java
input signal CONVEYOR_STATUS_REQUEST;
output Integer signal CONVEYOR_STATUS;
```

### 7.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `CONVEYOR_STATUS_REQUEST` | `oSignal` | `ConveyorControllerCD.CONVEYOR_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11003` |
| `CONVEYOR_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 7.4 What the Conveyor owner must implement

- Use the exact Clock Domain name `ConveyorControllerCD`.
- Receive `CONVEYOR_STATUS_REQUEST` on port `11003`.
- Send `CONVEYOR_STATUS` to `CoordinatorCD.CONVEYOR_STATUS` on port `11001`.
- Return the current status without starting or advancing the Conveyor.
- Keep Conveyor sensors, motors, and Controller-to-Controller events outside
  this M1 contract.

## 8. CoordinatorCD ↔ RotaryTurntableControllerCD

### 8.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `ROTARY_STATUS_REQUEST` | Coordinator → Rotary Turntable | pure signal | `RotaryTurntableControllerCD` | `11009` | Request the current Rotary Turntable status. |
| `ROTARY_STATUS` | Rotary Turntable → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Rotary Turntable status code. |

### 8.2 Required SystemJ declarations

Current Coordinator side:

```java
output signal ROTARY_STATUS_REQUEST;
input Integer signal ROTARY_STATUS;
```

Required Rotary Turntable side:

```java
input signal ROTARY_STATUS_REQUEST;
output Integer signal ROTARY_STATUS;
```

### 8.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `ROTARY_STATUS_REQUEST` | `oSignal` | `RotaryTurntableControllerCD.ROTARY_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11009` |
| `ROTARY_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 8.4 What the Rotary Turntable owner must implement

- Use the exact Clock Domain name `RotaryTurntableControllerCD`.
- Receive `ROTARY_STATUS_REQUEST` on port `11009`.
- Send `ROTARY_STATUS` to `CoordinatorCD.ROTARY_STATUS` on port `11001`.
- Return the current status without rotating or advancing the machine as a
  side effect of polling.
- Keep alignment sensors, triggers, and other Plant signals outside this M1
  contract.

## 9. CoordinatorCD ↔ FillerAControllerCD

### 9.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `FILL_A_RATIO` | Coordinator → Filler A | `Integer` | `FillerAControllerCD` | `11004` | Set the Liquid A percentage for the current product batch. |
| `FILLER_A_STATUS_REQUEST` | Coordinator → Filler A | pure signal | `FillerAControllerCD` | `11004` | Request the current Filler A status. |
| `FILLER_A_STATUS` | Filler A → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Filler A status code. |

`FILL_A_RATIO` is the Liquid A percentage for the current product batch and is
an integer in the current interface (`0` to `100`). The Coordinator emits it
when a product batch is started; it is not a valve command.

### 9.2 Required SystemJ declarations

Current Coordinator side:

```java
output Integer signal FILL_A_RATIO;
output signal FILLER_A_STATUS_REQUEST;
input Integer signal FILLER_A_STATUS;
```

Required Filler A side:

```java
input Integer signal FILL_A_RATIO;
input signal FILLER_A_STATUS_REQUEST;
output Integer signal FILLER_A_STATUS;
```

### 9.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `FILL_A_RATIO` | `oSignal` | `FillerAControllerCD.FILL_A_RATIO` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11004` |
| `FILLER_A_STATUS_REQUEST` | `oSignal` | `FillerAControllerCD.FILLER_A_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11004` |
| `FILLER_A_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 9.4 What the Filler A owner must implement

- Use the exact Clock Domain name `FillerAControllerCD`.
- Receive `FILL_A_RATIO` and `FILLER_A_STATUS_REQUEST` on port `11004`.
- Retain/use the received percentage as the current product recipe input.
- Send `FILLER_A_STATUS` to `CoordinatorCD.FILLER_A_STATUS` on port `11001`.
- Do not treat recipe input or status polling as a direct valve command.

## 10. CoordinatorCD ↔ FillerBControllerCD

### 10.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `FILL_B_RATIO` | Coordinator → Filler B | `Integer` | `FillerBControllerCD` | `11005` | Set the Liquid B percentage for the current product batch. |
| `FILLER_B_STATUS_REQUEST` | Coordinator → Filler B | pure signal | `FillerBControllerCD` | `11005` | Request the current Filler B status. |
| `FILLER_B_STATUS` | Filler B → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Filler B status code. |

`FILL_B_RATIO` is the Liquid B percentage for the current product batch and is
an integer in the current interface (`0` to `100`). The Coordinator emits it
when a product batch is started; it is not a valve command.

### 10.2 Required SystemJ declarations

Current Coordinator side:

```java
output Integer signal FILL_B_RATIO;
output signal FILLER_B_STATUS_REQUEST;
input Integer signal FILLER_B_STATUS;
```

Required Filler B side:

```java
input Integer signal FILL_B_RATIO;
input signal FILLER_B_STATUS_REQUEST;
output Integer signal FILLER_B_STATUS;
```

### 10.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `FILL_B_RATIO` | `oSignal` | `FillerBControllerCD.FILL_B_RATIO` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11005` |
| `FILLER_B_STATUS_REQUEST` | `oSignal` | `FillerBControllerCD.FILLER_B_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11005` |
| `FILLER_B_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 10.4 What the Filler B owner must implement

- Use the exact Clock Domain name `FillerBControllerCD`.
- Receive `FILL_B_RATIO` and `FILLER_B_STATUS_REQUEST` on port `11005`.
- Retain/use the received percentage as the current product recipe input.
- Send `FILLER_B_STATUS` to `CoordinatorCD.FILLER_B_STATUS` on port `11001`.
- Do not treat recipe input or status polling as a direct valve command.

## 11. CoordinatorCD ↔ LidLoaderControllerCD

### 11.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `LID_STATUS_REQUEST` | Coordinator → Lid Loader | pure signal | `LidLoaderControllerCD` | `11006` | Request the current Lid Loader status. |
| `LID_STATUS` | Lid Loader → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Lid Loader status code. |

### 11.2 Required SystemJ declarations

Current Coordinator side:

```java
output signal LID_STATUS_REQUEST;
input Integer signal LID_STATUS;
```

Required Lid Loader side:

```java
input signal LID_STATUS_REQUEST;
output Integer signal LID_STATUS;
```

### 11.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `LID_STATUS_REQUEST` | `oSignal` | `LidLoaderControllerCD.LID_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11006` |
| `LID_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 11.4 What the Lid Loader owner must implement

- Use the exact Clock Domain name `LidLoaderControllerCD`.
- Receive `LID_STATUS_REQUEST` on port `11006`.
- Send `LID_STATUS` to `CoordinatorCD.LID_STATUS` on port `11001`.
- Return the current status without placing a lid or otherwise advancing the
  machine as a polling side effect.

The exact current names are `LID_STATUS_REQUEST` and `LID_STATUS`.

## 12. CoordinatorCD ↔ CapperControllerCD

### 12.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `CAPPER_STATUS_REQUEST` | Coordinator → Capper | pure signal | `CapperControllerCD` | `11007` | Request the current Capper status. |
| `CAPPER_STATUS` | Capper → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Capper status code. |

### 12.2 Required SystemJ declarations

Current Coordinator side:

```java
output signal CAPPER_STATUS_REQUEST;
input Integer signal CAPPER_STATUS;
```

Required Capper side:

```java
input signal CAPPER_STATUS_REQUEST;
output Integer signal CAPPER_STATUS;
```

### 12.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `CAPPER_STATUS_REQUEST` | `oSignal` | `CapperControllerCD.CAPPER_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11007` |
| `CAPPER_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 12.4 What the Capper owner must implement

- Use the exact Clock Domain name `CapperControllerCD`.
- Receive `CAPPER_STATUS_REQUEST` on port `11007`.
- Send `CAPPER_STATUS` to `CoordinatorCD.CAPPER_STATUS` on port `11001`.
- Return the current status without starting or advancing the Capper.
- Do not send `BOTTLE_DONE`; the current M1 contract assigns that event to the
  Bottle Unloader.

## 13. CoordinatorCD ↔ BottleUnloaderControllerCD

### 13.1 Signals

| Signal | Direction | SystemJ type | Receiver Clock Domain | Receiver port | Purpose |
|---|---|---|---|---:|---|
| `UNLOADER_STATUS_REQUEST` | Coordinator → Bottle Unloader | pure signal | `BottleUnloaderControllerCD` | `11010` | Request the current Bottle Unloader status. |
| `UNLOADER_STATUS` | Bottle Unloader → Coordinator | `Integer` | `CoordinatorCD` | `11001` | Return the current Bottle Unloader status code. |
| `BOTTLE_DONE` | Bottle Unloader → Coordinator | pure signal | `CoordinatorCD` | `11001` | Report one finished bottle successfully collected/unloaded. |

One `BOTTLE_DONE` means one finished bottle has reached the
collection/unloading stage and has been successfully collected. The
Coordinator increments the current product's completed-bottle count once for
each event. A Capper completion or a bottle merely leaving an upstream station
is not `BOTTLE_DONE`.

### 13.2 Required SystemJ declarations

Current Coordinator side:

```java
output signal UNLOADER_STATUS_REQUEST;
input Integer signal UNLOADER_STATUS;
input signal BOTTLE_DONE;
```

Required Bottle Unloader side:

```java
input signal UNLOADER_STATUS_REQUEST;
output Integer signal UNLOADER_STATUS;
output signal BOTTLE_DONE;
```

### 13.3 Coordinator XML mapping

| Signal | XML element | Destination / listener | Class | IP | Port |
|---|---|---|---|---|---:|
| `UNLOADER_STATUS_REQUEST` | `oSignal` | `BottleUnloaderControllerCD.UNLOADER_STATUS_REQUEST` | `com.systemj.ipc.SimpleClient` | `127.0.0.1` | `11010` |
| `UNLOADER_STATUS` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |
| `BOTTLE_DONE` | `iSignal` | `CoordinatorCD` listener | `com.systemj.ipc.SimpleServer` | `127.0.0.1` | `11001` |

### 13.4 What the Bottle Unloader owner must implement

- Use the exact Clock Domain name `BottleUnloaderControllerCD`.
- Receive `UNLOADER_STATUS_REQUEST` on port `11010`.
- Send `UNLOADER_STATUS` to `CoordinatorCD.UNLOADER_STATUS` on port `11001`.
- Send exactly one `BOTTLE_DONE` to `CoordinatorCD.BOTTLE_DONE` on port `11001`
  for each successfully collected finished bottle.
- Do not use a status poll as a trigger for collection or for `BOTTLE_DONE`.

## 14. CoordinatorCD → ABSVisualisationPlantCD

This boundary exists in the current `xuqi_coordinator` implementation.

### 14.1 Signals

All signals below are `Integer` outputs from `CoordinatorCD` to
`ABSVisualisationPlantCD` at `127.0.0.1:11008`.

| Signal | Purpose |
|---|---|
| `VIZ_LOADER_STATUS` | Display the latest Bottle Loader status. |
| `VIZ_CONVEYOR_STATUS` | Display the latest Conveyor status. |
| `VIZ_ROTARY_STATUS` | Display the latest Rotary Turntable status. |
| `VIZ_FILLER_A_STATUS` | Display the latest Filler A status. |
| `VIZ_FILLER_B_STATUS` | Display the latest Filler B status. |
| `VIZ_LID_STATUS` | Display the latest Lid Loader status. |
| `VIZ_CAPPER_STATUS` | Display the latest Capper status. |
| `VIZ_UNLOADER_STATUS` | Display the latest Bottle Unloader status. |
| `VIZ_REQUIRED_BOTTLES` | Display the current product batch target. |
| `VIZ_COMPLETED_BOTTLES` | Display collected-bottle progress for the current product batch. |

### 14.2 Required SystemJ declarations

Current Coordinator side:

```java
output Integer signal VIZ_LOADER_STATUS;
output Integer signal VIZ_CONVEYOR_STATUS;
output Integer signal VIZ_ROTARY_STATUS;
output Integer signal VIZ_FILLER_A_STATUS;
output Integer signal VIZ_FILLER_B_STATUS;
output Integer signal VIZ_LID_STATUS;
output Integer signal VIZ_CAPPER_STATUS;
output Integer signal VIZ_UNLOADER_STATUS;
output Integer signal VIZ_REQUIRED_BOTTLES;
output Integer signal VIZ_COMPLETED_BOTTLES;
```

Required Visualisation side:

```java
input Integer signal VIZ_LOADER_STATUS;
input Integer signal VIZ_CONVEYOR_STATUS;
input Integer signal VIZ_ROTARY_STATUS;
input Integer signal VIZ_FILLER_A_STATUS;
input Integer signal VIZ_FILLER_B_STATUS;
input Integer signal VIZ_LID_STATUS;
input Integer signal VIZ_CAPPER_STATUS;
input Integer signal VIZ_UNLOADER_STATUS;
input Integer signal VIZ_REQUIRED_BOTTLES;
input Integer signal VIZ_COMPLETED_BOTTLES;
```

### 14.3 Coordinator XML mapping

Each current Coordinator mapping is an `oSignal` using
`com.systemj.ipc.SimpleClient`, IP `127.0.0.1`, port `11008`:

| Signal | Exact `To` value |
|---|---|
| `VIZ_LOADER_STATUS` | `ABSVisualisationPlantCD.VIZ_LOADER_STATUS` |
| `VIZ_CONVEYOR_STATUS` | `ABSVisualisationPlantCD.VIZ_CONVEYOR_STATUS` |
| `VIZ_ROTARY_STATUS` | `ABSVisualisationPlantCD.VIZ_ROTARY_STATUS` |
| `VIZ_FILLER_A_STATUS` | `ABSVisualisationPlantCD.VIZ_FILLER_A_STATUS` |
| `VIZ_FILLER_B_STATUS` | `ABSVisualisationPlantCD.VIZ_FILLER_B_STATUS` |
| `VIZ_LID_STATUS` | `ABSVisualisationPlantCD.VIZ_LID_STATUS` |
| `VIZ_CAPPER_STATUS` | `ABSVisualisationPlantCD.VIZ_CAPPER_STATUS` |
| `VIZ_UNLOADER_STATUS` | `ABSVisualisationPlantCD.VIZ_UNLOADER_STATUS` |
| `VIZ_REQUIRED_BOTTLES` | `ABSVisualisationPlantCD.VIZ_REQUIRED_BOTTLES` |
| `VIZ_COMPLETED_BOTTLES` | `ABSVisualisationPlantCD.VIZ_COMPLETED_BOTTLES` |

The Visualisation is a receiver/display boundary. This contract defines no
Visualisation-to-Coordinator feedback signal.

## 15. Controller-side XML implementation rule

Each Controller owner must mirror the direction of the current Coordinator
mapping:

- A signal sent by the Coordinator must be an `iSignal` using
  `com.systemj.ipc.SimpleServer` in the receiving Controller's XML, listening
  on that Controller's assigned receiver port.
- A status or `BOTTLE_DONE` sent to the Coordinator must be an `oSignal` using
  `com.systemj.ipc.SimpleClient`, with the exact `To` value shown in this
  contract and destination `127.0.0.1:11001`.
- The Controller XML Clock Domain `Name` must exactly match the receiver Clock
  Domain shown in this contract.
- Signal spelling and case must match exactly on the Controller `.sysj`, the
  Controller XML, `CoordinatorCD`, and `xuqi_coordinator/coordinator.xml`.

## 16. Integration Issues / Mismatches Found

### 16.1 Xuqi source/XML/Excel

No mismatch was found among the current Xuqi POS SystemJ/XML, current Xuqi
Coordinator SystemJ/XML, and `COMPSYS704_Project1_Interface_V1.xlsx` for:

- the POS and Coordinator Clock Domains and ports;
- all eight current Coordinator-facing Machine Controllers;
- all current signal names and SystemJ types;
- all current Machine Controller receiver ports;
- the Bottle Unloader ownership of `BOTTLE_DONE`; and
- all ten current ABS Visualisation signals.

### 16.2 Member 3 comparison

No Member 3 integration contract or Member 3 module was present in the
inspected current project, so Member 3's current M3 ↔ M1 boundary cannot be
verified from the available files. This is an unresolved integration-check
item, not evidence that Member 3 currently matches or mismatches M1.

The following compatibility requirements are explicit:

- `TRANSPORT_STATUS_REQUEST` and `TRANSPORT_STATUS` are not part of Xuqi's
  current M1 interface. If Member 3 still uses either old name, it does not
  match this contract.
- The current transport-related M1 boundaries are separately named
  `CONVEYOR_STATUS_REQUEST` / `CONVEYOR_STATUS` and
  `ROTARY_STATUS_REQUEST` / `ROTARY_STATUS`.
- The current Lid Loader boundary is exactly `LID_STATUS_REQUEST` /
  `LID_STATUS`. A different Lid status name does not match M1.
- Member 3 does not need to expose its Controller-to-Controller,
  Controller-to-Plant, M2-facing, or M4-facing internal signals in this M1
  contract. Only the applicable Coordinator-facing signals above must match.

Before integration, the Member 3 owner must compare its actual `.sysj` and XML
against the applicable sections of this contract.

## 17. Minimum Integration Test

Run the real receiver runtimes with the exact Clock Domain names and ports in
this contract, then verify this minimum end-to-end path:

```text
POSCD
  -> ORDER
CoordinatorCD
  -> START_ORDER to BottleLoaderControllerCD
  -> FILL_A_RATIO to FillerAControllerCD
  -> FILL_B_RATIO to FillerBControllerCD
  -> eight *_STATUS_REQUEST polling events
real Machine Controllers
  -> eight matching *_STATUS Integer replies
production pipeline completes one bottle
BottleUnloaderControllerCD
  -> BOTTLE_DONE
CoordinatorCD
  -> counts collected bottles
  -> ORDER_COMPLETE after the full order is complete
POSCD
```

Minimum checks:

1. POS sends a valid `ORDER` to `CoordinatorCD` on port `11001`.
2. The Coordinator sends `START_ORDER` and the two recipe ratios to the exact
   receiving Clock Domains and ports defined above.
3. Every real Machine Controller receives its status poll without changing
   physical operation and replies to `CoordinatorCD` on port `11001` with a
   valid status code from `0` to `4`.
4. Only `BottleUnloaderControllerCD` sends one `BOTTLE_DONE` per successfully
   collected bottle.
5. After all required bottles/products are complete, the Coordinator sends
   `orderId|COMPLETED|completionTimeSeconds` to `POSCD` on port `11000`.
6. If ABS Visualisation is running, it receives all current status and progress
   signals on port `11008` without affecting production control.
