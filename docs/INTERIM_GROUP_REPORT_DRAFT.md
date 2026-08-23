# COMPSYS 704 Project 1

# Interim Group Project Report

## Extended Automated Bottling System and Purchase Order System

| Report information | Details |
| --- | --- |
| Group number | [TEAM TO COMPLETE] |
| Student names | [TEAM TO COMPLETE] |
| Student IDs | [TEAM TO COMPLETE] |
| Date | [TEAM TO COMPLETE] |
| Repository/version | [TEAM TO COMPLETE] |

> **Draft status.** This document is a shared interim-report framework. Sections describing the current POS and ABS Coordinator are populated from the repository's Integration V1 implementation and frozen interface documents. Machine Controller and Plant sections remain deliberately incomplete until their responsible team members provide verified designs, implementation evidence and test results.

## 1. Introduction

The COMPSYS 704 Project 1 system is a preliminary design and simulation model of an Extended Automated Bottling System (EABS) integrated with a Purchase Order System (POS). The EABS transforms empty bottles into completed product instances by transporting each bottle through filling, lid-placement, capping and unloading operations. The POS provides the external order-entry boundary: it captures the products requested by a customer, the number of instances required and the two-liquid recipe for each product. The project therefore covers both customer-facing order submission and the coordinated operation of a distributed manufacturing model.

The design uses SystemJ and its Globally Asynchronous, Locally Synchronous (GALS) model of computation. Functional components are represented as Clock Domains that execute locally synchronous reactive behaviour and communicate across asynchronous subsystem boundaries through typed SystemJ signals. This structure supports concurrency, explicit interfaces and independent development of components. It also permits simulated Plant models and their Controllers to be integrated before any physical manufacturing platform is available.

Each intelligent machine is intended to have a Controller and a Plant model. A machine Controller owns the local behavioural sequence for its device and interacts with the Plant's sensors and actuators. At the system-integration level, the ABS Coordinator receives purchase orders, distributes current-batch parameters, supervises Controller status, counts completed bottles and informs the POS when a complete purchase order has finished. The Coordinator does not issue valve, motor, cylinder or other Plant-level commands.

The present repository establishes an Integration V1 baseline for the POS, Coordinator, Coordinator-facing machine interfaces, display-only system visualisation and test-only Mock Controller infrastructure. It does not provide evidence that all real Machine Controller/Plant modules or the fully integrated EABS production pipeline are complete. Those parts are identified as planned work throughout this report.

## 2. System Requirements and Scope

The 2026 project brief requires a conceptual design and SystemJ model of the core ABS and its relationship with the POS. At a high level, one product instance follows this production concept:

**Empty bottle -> Conveyor -> Rotary Table -> Filling -> Lid placement -> Capping -> Unloading**

The Bottle Loader places an empty bottle on the input side of the production system. The Conveyor transports bottles to and from the rotary section. The Rotary Turntable indexes bottles between processing stations. The filling stage supplies the required liquid mixture, after which the Lid Loader places a lid and the Capper secures it. A completed bottle is then transported to and collected by the Bottle Unloader. The brief also requires that the final design support multiple bottles at different stations concurrently without overloading the system. How individual Controllers synchronise this physical pipeline remains a team integration topic and is not assigned to the Coordinator.

For the 2026 project, the filling design uses two liquids. The current team architecture exposes two logical filling paths and Coordinator-facing Controllers, Filler A and Filler B. Their proportions originate in the customer order and are represented as integer percentages that must total 100. This report does not describe a third implemented filler. The team must still document how the two real filler implementations coordinate with bottle presence, Plant sensors and one another while preventing overflow.

A purchase order can contain more than one product. Each product record defines a product identifier, Liquid A percentage, Liquid B percentage and quantity. In the current implementation, quantity is the number of bottles or product instances in that product batch; it is not a volume in millilitres. Production is organised into batches of identical product instances. The current Coordinator completes the active product batch before it dispatches the parameters for the next product in the same order. Multiple bottles may nevertheless be in flight concurrently within the production system, subject to the local machine-control and synchronisation design.

The current Order V1 parser supports between one and four product records per purchase order. This is an implementation limit of the present POS/Coordinator protocol, not a general claim about a future commercial POS. Order completion is counted at the collection boundary: one `BOTTLE_DONE` event means that one finished bottle has reached and been collected by the Bottle Unloader. Completion of the Capper alone is not treated as completion of a product instance.

The GP scope at this milestone comprises the overall conceptual architecture, component boundaries, control strategy, interface definitions, current implementation progress and the plan for integrating real Machine Controller/Plant pairs. Individual-project extensions may later augment the core model, but they must not be presented as completed GP functionality unless they have been explicitly integrated and validated.

## 3. Overall Conceptual Architecture

The current conceptual architecture is layered as follows:

1. **POS:** captures, validates and submits customer purchase orders.
2. **ABS Coordinator:** converts an accepted order into current-batch parameters, monitors machine status and tracks order-level progress.
3. **Machine Controllers:** execute local device behaviour and coordinate the manufacturing pipeline through their defined machine-level interfaces.
4. **Machine Plant Models:** simulate sensors, actuators and physical effects associated with each intelligent machine.
5. **ABS Visualisation:** displays machine status and current batch progress received from the Coordinator without controlling production.

**Figure 1. Overall EABS + POS Conceptual Architecture**

`[TEAM FIGURE TO BE INSERTED HERE]`

**Suggested caption:** *Conceptual architecture of the EABS and POS, showing SystemJ Clock Domains, the system-level Coordinator, local Machine Controller/Plant pairs, typed communication interfaces and the read-only visualisation path.*

Figure 1 should show `POSCD` exchanging `ORDER` and `ORDER_COMPLETE` with `CoordinatorCD`. The Coordinator should connect to eight logical Machine Controllers: Bottle Loader, Conveyor, Rotary Turntable, Filler A, Filler B, Lid Loader, Capper and Bottle Unloader. Each real Controller should be shown beside its own Plant model, with Controller-to-Plant sensor and actuator interfaces clearly separated from the Coordinator-facing contract. Status feedback should return from Controllers to the Coordinator, while `BOTTLE_DONE` should originate at the Bottle Unloader boundary.

The figure should also show the one-way display path from `CoordinatorCD` to `ABSVisualisationPlantCD`. This separation is important: the Visualisation consumes machine-state and batch-progress information, but it neither polls the Machine Controllers nor issues production commands. The present local integration mapping uses `127.0.0.1` and separate receiver ports to simulate distributed SystemJ runtimes on the development computer.

## 4. Overall Control Strategy

The team design uses centralised coordination at the system-integration level and distributed control at the machine level. `CoordinatorCD` is the system orchestrator for purchase orders, recipes, batch quantities, machine-state supervision and purchase-order completion. It establishes what product batch is required and observes whether the system is progressing, but it does not encode the physical sequence of opening valves, running motors, rotating the table, placing lids or operating the Capper.

The local Machine Controllers retain responsibility for those physical behaviours. Each Controller must react to its Plant sensors, command its Plant actuators, enforce its own safe sequence and participate in the Controller-to-Controller synchronisation needed to move bottles through the EABS. A status request from the Coordinator is strictly a query; it must not start, continue or advance a machine operation. This boundary prevents system supervision from being coupled accidentally to physical progress.

The Coordinator implementation contains independent concurrent SystemJ behaviours for order handling, periodic polling, status reception, completion notification and bottle counting. Consequently, waiting for one type of event does not create a single serial state machine that blocks every other integration activity. The Machine Controllers can continue their local concurrent operation while the Coordinator polls status, updates the display and listens for completion events.

This division is useful in a GALS design for three reasons. First, it gives each Clock Domain a focused responsibility and stable interface. Second, machine owners can develop and test Controller/Plant pairs independently of the POS. Third, changes to local actuator sequences need not alter the order protocol, provided the frozen Coordinator-facing contract remains satisfied. The result is a mixed control strategy: centralised order-level orchestration combined with distributed machine-level control.

## 5. Purchase Order System (POS)

### 5.1 POS Purpose

The POS is the customer-facing entry point to production. The current implementation comprises the `POSCD` SystemJ Clock Domain and a handwritten Swing class, `POSVisualisation`. The Swing interface presents an order form, queues a validated payload for the SystemJ reaction loop and displays submission or completion feedback. The Java GUI does not create its own network connection; `POSCD` remains the sender and receiver at the frozen SystemJ interface.

The GUI starts asynchronously through `POSVisualisation.start()`. Normal interactive operation waits for the user to select **Submit Order**. A separate delayed system-property input exists only to support headless integration testing; it is not an automatically generated customer order in normal operation.

### 5.2 POS Order Information

The current form captures:

- Order ID;
- Product ID;
- quantity;
- Liquid A percentage; and
- Liquid B percentage.

The source currently creates one product-entry row in the GUI, while the Order V1 representation and parser support up to four product records. Quantity is validated as a positive integer and represents the required number of bottles for that product. The present protocol contains no bottle-volume or absolute millilitre field. The two liquid entries are recipe percentages between 0 and 100 inclusive and must sum to 100.

Order and product identifiers must be non-empty protocol tokens. The form rejects identifiers containing `|`, `,` or `;` because those characters delimit the Order V1 payload.

### 5.3 ORDER V1 Format

The frozen Order V1 format is:

```text
orderId|productCount|productId,A%,B%,quantity;...
```

For example:

```text
PO001|1|P1,60,40,2
```

This payload means that order `PO001` contains one product record. Product `P1` requires two bottles, with 60% Liquid A and 40% Liquid B. When an order contains multiple products, product records are separated by semicolons, for example:

```text
PO-MULTI|2|P1,60,40,2;P2,25,75,1
```

The encoded number of product records must equal `productCount`. `OrderV1.MAX_PRODUCTS` is currently four.

### 5.4 POS Validation

Validation is deliberately performed at two boundaries. First, `POSVisualisation.queueOrderFromForm()` validates the interactive fields. It checks protocol-safe identifiers, integer input, positive quantity, percentage ranges and the requirement that Liquid A plus Liquid B equals 100. If validation succeeds, it constructs the payload and atomically places it in the pending-order slot. If another submission is already pending, the form reports an error instead of overwriting it.

Second, the `POSCD` reaction obtains a pending payload through `POSVisualisation.pollSubmittedOrder()` and calls `OrderV1.parse(submittedOrder)`. Only a payload accepted by this parser is emitted. This SystemJ-boundary validation prevents malformed data from being sent even if it is injected through the test-only property path or another non-GUI source. Invalid input invokes `showValidationError()` and no `ORDER` signal is transmitted.

### 5.5 POS to Coordinator Communication

The POS declares:

```java
output String signal ORDER;
```

After boundary validation, it submits the order with:

```java
emit ORDER(submittedOrder);
```

The production XML maps this output from `POSCD` to `CoordinatorCD.ORDER` using a `SimpleClient` at `127.0.0.1:11001`. The Coordinator independently validates the same payload after receipt. This means POS validation provides immediate user feedback, while Coordinator validation protects the production boundary.

### 5.6 Coordinator to POS Completion

The POS also declares:

```java
input String signal ORDER_COMPLETE;
```

Its reaction checks `present(ORDER_COMPLETE)`, reads `#ORDER_COMPLETE` and passes the value to `POSVisualisation.handleCompletion(...)`. The completion format is:

```text
orderId|COMPLETED|completionTimeSeconds
```

The handler verifies that the returned order identifier matches the active order, the state is exactly `COMPLETED` and the time is a non-negative integer. The current transport strategy may deliver the identical completion payload more than once; the POS records the handled payload and ignores duplicate copies for display purposes. A valid completion updates the Swing view with the order identifier, status and completion time, then re-enables submission after a short UI delay.

`ORDER_COMPLETE` is mapped from `CoordinatorCD` to the `POSCD` `SimpleServer` at `127.0.0.1:11000`.

## 6. ABS Coordinator

### 6.1 Coordinator Purpose

The ABS Coordinator is the integration boundary between the POS, eight logical Machine Controllers and the read-only system visualisation. Its state is held in `CoordinatorStateV1` so that independent SystemJ parallel branches can share the accepted order, current product index, recipe percentages, batch counts, status values and delayed-completion state.

The Coordinator performs order-level orchestration only. It does not control a Plant, define motor/valve signals or prescribe the internal movement of a bottle from one Controller to the next. Those responsibilities remain within the machine modules and their integration design.

### 6.2 Order Reception

`CoordinatorCD` declares `input String signal ORDER`. Its order-handling branch uses `present(ORDER)` and reads the value with:

```java
String receivedOrder = #ORDER;
```

The Coordinator rejects a new order if `orderActive` is already true or if completion notification for the previous order is still pending. Otherwise, it calls `CoordinatorStateV1.accept(receivedOrder)`. This method performs an independent `OrderV1.parse()` operation, initialises the first product record, records the order start time and sets `orderActive` only when the payload is valid. An invalid order is logged and produces no production dispatch.

### 6.3 Initial Batch Dispatch

For an accepted order, the Coordinator emits three valued integer signals:

| Signal | Meaning |
| --- | --- |
| `START_ORDER` | Number of bottles required for the current product batch |
| `FILL_A_RATIO` | Liquid A percentage for the current product batch |
| `FILL_B_RATIO` | Liquid B percentage for the current product batch |

`START_ORDER` is sent to `BottleLoaderControllerCD`; it is a batch quantity, not a per-bottle start pulse. `FILL_A_RATIO` and `FILL_B_RATIO` are sent to the two filler Controllers as recipe configuration. They are percentages, not absolute liquid volumes and not direct valve commands. The same accepted-order reaction also emits `VIZ_REQUIRED_BOTTLES` and initialises `VIZ_COMPLETED_BOTTLES` to zero.

### 6.4 Periodic Controller Status Polling

Status polling executes in a parallel branch. The branch compares the wall-clock time with `CoordinatorStateV1.nextStatusPollMillis`. When the deadline is reached, the Coordinator emits all eight pure status-request signals:

- `LOADER_STATUS_REQUEST`;
- `CONVEYOR_STATUS_REQUEST`;
- `ROTARY_STATUS_REQUEST`;
- `FILLER_A_STATUS_REQUEST`;
- `FILLER_B_STATUS_REQUEST`;
- `LID_STATUS_REQUEST`;
- `CAPPER_STATUS_REQUEST`; and
- `UNLOADER_STATUS_REQUEST`.

It then updates the next deadline to approximately the current time plus 1,000 ms. Using wall-clock rate limiting prevents the fast logical reaction loop from flooding external SystemJ signal transport. This is active polling by the Coordinator. Each request asks for the current state only and must be side-effect free with respect to physical operation.

The polling branch also periodically re-emits the current required and completed bottle counts for the display. Polling is independent of order reception and bottle-completion processing.

### 6.5 Controller Status Feedback

Status reception is separate from status request emission. Eight parallel listeners wait for the corresponding valued integer replies:

- `LOADER_STATUS`;
- `CONVEYOR_STATUS`;
- `ROTARY_STATUS`;
- `FILLER_A_STATUS`;
- `FILLER_B_STATUS`;
- `LID_STATUS`;
- `CAPPER_STATUS`; and
- `UNLOADER_STATUS`.

Conceptually, each listener performs `present(XXX_STATUS)`, reads `#XXX_STATUS`, compares the value with the stored state and updates the corresponding field in `CoordinatorStateV1` when it has changed. The frozen status convention is `0 = IDLE`, `1 = READY`, `2 = BUSY`, `3 = DONE` and `4 = FAULT`. Real Controller owners must implement the same names and integer convention. The initial visual label `WAITING` is not an additional status code.

The distinction between request and response is fundamental: the polling branch asks for status, while the status-listener branch receives and stores the reply. A `*_STATUS_REQUEST` must never be interpreted as a production trigger.

### 6.6 Visualisation Information

After receiving a Controller status, the Coordinator forwards the current value through the matching display signal:

- `VIZ_LOADER_STATUS`;
- `VIZ_CONVEYOR_STATUS`;
- `VIZ_ROTARY_STATUS`;
- `VIZ_FILLER_A_STATUS`;
- `VIZ_FILLER_B_STATUS`;
- `VIZ_LID_STATUS`;
- `VIZ_CAPPER_STATUS`; and
- `VIZ_UNLOADER_STATUS`.

It also supplies `VIZ_REQUIRED_BOTTLES` and `VIZ_COMPLETED_BOTTLES`. All ten signals are integer outputs mapped to `ABSVisualisationPlantCD` at port 11008. The visualisation is a display-only consumer: it does not poll Controllers and it sends no machine-control feedback to the Coordinator. This section describes the GP integration boundary only; further symbolic or workpiece-position visualisation belongs to a separately identified extension until integrated into the core GP.

### 6.7 Bottle Completion and Batch Progress

The Bottle Unloader reports one successfully collected finished bottle through the pure `BOTTLE_DONE` signal. In a separate parallel branch, the Coordinator checks `present(BOTTLE_DONE)` and processes the event only while an order is active. `CoordinatorStateV1.recordBottleDone()` increments `completedBottles` and reports that the product batch is complete when the count reaches `requiredBottles`.

After every accepted completion event, the Coordinator emits the new `VIZ_COMPLETED_BOTTLES` value. If the batch is not yet complete, the branch returns to waiting while all other Coordinator behaviours and local Machine Controllers remain able to react. This order-level count does not replace bottle-position or machine-safety logic inside the production pipeline.

### 6.8 Multiple Product Handling

When the current batch reaches its required count, `hasNextProduct()` determines whether another product record remains in the purchase order. If so, `advanceToNextProduct()` increments the product index and loads the next product's identifier, quantity and two percentages. The Coordinator then emits a new `START_ORDER`, `FILL_A_RATIO` and `FILL_B_RATIO`, updates `VIZ_REQUIRED_BOTTLES` and resets `VIZ_COMPLETED_BOTTLES` to zero.

This behaviour enforces the brief's product-batch ordering at the Coordinator boundary: the next product batch is not dispatched until the preceding batch has produced all required `BOTTLE_DONE` events. It does not prevent the Machine Controllers from pipelining multiple bottles belonging to the active batch.

### 6.9 Purchase Order Completion

If no product remains, `completeOrder()` calculates elapsed order time in whole seconds and constructs:

```text
orderId|COMPLETED|completionTimeSeconds
```

It releases the active-order flag, sets `completionPending` and schedules notification. A separate parallel branch waits for the completion-send deadline, calls `nextCompletionTransmission()` to obtain the stored payload and emits `ORDER_COMPLETE(completionPayload)` to the POS. Separating this transmission from the final `BOTTLE_DONE` reaction avoids coupling the final-machine input timing directly to the POS transport event.

The present implementation allows up to three transmissions of the same logical payload for course-runtime connection timing tolerance. The first is scheduled after approximately 250 ms and subsequent copies approximately 500 ms apart. This is one logical completion; the POS de-duplicates identical copies. The timing detail is an implementation measure rather than a change to the frozen completion protocol.

## 7. POS–Coordinator Integration Flow

The integrated flow covers the complete lifecycle of a customer order: customer data is validated by the POS, encoded as Order V1 and emitted through `ORDER`; the Coordinator validates and dispatches batch parameters to the Machine Controllers; status and progress are returned and displayed; Bottle Unloader events complete each batch; and the final `ORDER_COMPLETE` payload is returned to the POS.

---

**Figure 2. Integrated POS–Coordinator–Controller Flow**

`[INSERT POS_COORDINATOR_FLOWCHART.drawio / exported PNG HERE]`

**Recommended figure file:**
`docs/POS_COORDINATOR_FLOWCHART.drawio`

**Figure caption:**
*Integrated flow of order submission, Coordinator orchestration, Controller status monitoring, production progress and purchase-order completion notification.*

---

Figure 2 should be read across the POS, Coordinator, Machine Controller and ABS Visualisation lanes. The prominent `ORDER : String` path marks the transition from customer order entry to production orchestration. Production commands and recipes then cross from the Coordinator to the appropriate Controllers, while status replies and `BOTTLE_DONE` return toward the Coordinator.

The return path is equally important. Machine status and batch progress are forwarded to the read-only visualisation, but the completed purchase order is returned to the POS through `ORDER_COMPLETE : String`. The figure therefore distinguishes production control, supervision, display and customer notification rather than collapsing them into one serial Controller.

## 8. Coordinator–Controller Interface

The Coordinator-facing contract is frozen as Integration V1 so that Machine Controller owners can develop independently. A change to a signal name, SystemJ type, Clock Domain or receiver port requires team agreement and coordinated updates to source, XML, tests and documentation. Controller-to-Controller signals and Controller-to-Plant sensor/actuator signals are intentionally outside this table.

### 8.1 Coordinator to Controllers

| Receiver | Signal | SystemJ type | Receiver Clock Domain | Port | Purpose |
| --- | --- | --- | --- | ---: | --- |
| Bottle Loader | `START_ORDER` | `Integer` | `BottleLoaderControllerCD` | 11002 | Current product-batch bottle quantity |
| Bottle Loader | `LOADER_STATUS_REQUEST` | pure signal | `BottleLoaderControllerCD` | 11002 | Request current Loader status |
| Conveyor | `CONVEYOR_STATUS_REQUEST` | pure signal | `ConveyorControllerCD` | 11003 | Request current Conveyor status |
| Rotary Turntable | `ROTARY_STATUS_REQUEST` | pure signal | `RotaryTurntableControllerCD` | 11009 | Request current Rotary status |
| Filler A | `FILL_A_RATIO` | `Integer` | `FillerAControllerCD` | 11004 | Current Liquid A percentage |
| Filler A | `FILLER_A_STATUS_REQUEST` | pure signal | `FillerAControllerCD` | 11004 | Request current Filler A status |
| Filler B | `FILL_B_RATIO` | `Integer` | `FillerBControllerCD` | 11005 | Current Liquid B percentage |
| Filler B | `FILLER_B_STATUS_REQUEST` | pure signal | `FillerBControllerCD` | 11005 | Request current Filler B status |
| Lid Loader | `LID_STATUS_REQUEST` | pure signal | `LidLoaderControllerCD` | 11006 | Request current Lid Loader status |
| Capper | `CAPPER_STATUS_REQUEST` | pure signal | `CapperControllerCD` | 11007 | Request current Capper status |
| Bottle Unloader | `UNLOADER_STATUS_REQUEST` | pure signal | `BottleUnloaderControllerCD` | 11010 | Request current Unloader status |

All mappings use `127.0.0.1`; the port belongs to the receiving Controller runtime. `START_ORDER` and the ratio signals configure the current batch. Every `*_STATUS_REQUEST` is polling only and must not start or advance a machine operation.

### 8.2 Controllers to Coordinator

| Sender | Signal | SystemJ type | Receiver Clock Domain | Port | Purpose |
| --- | --- | --- | --- | ---: | --- |
| Bottle Loader | `LOADER_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Loader status code |
| Conveyor | `CONVEYOR_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Conveyor status code |
| Rotary Turntable | `ROTARY_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Rotary status code |
| Filler A | `FILLER_A_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Filler A status code |
| Filler B | `FILLER_B_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Filler B status code |
| Lid Loader | `LID_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Lid Loader status code |
| Capper | `CAPPER_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Capper status code |
| Bottle Unloader | `UNLOADER_STATUS` | `Integer` | `CoordinatorCD` | 11001 | Current Unloader status code |
| Bottle Unloader | `BOTTLE_DONE` | pure signal | `CoordinatorCD` | 11001 | One finished bottle was collected |

The shared status values are `0 = IDLE`, `1 = READY`, `2 = BUSY`, `3 = DONE` and `4 = FAULT`. The real Controller XML must mirror the Coordinator mapping: Coordinator outputs become Controller `iSignal` definitions, while status and `BOTTLE_DONE` become Controller `oSignal` definitions addressed to `CoordinatorCD`.

## 9. Machine Controller and Plant Designs

The following sections are reserved for the responsible machine owners. They must be completed from actual `.sysj`, XML, Java support and test evidence. The prompts are requirements for contribution, not statements that an implementation exists.

### 9.1 Bottle Loader Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Loader's role, responsible student and Clock Domain(s).
- List Coordinator-facing and local Controller/Plant signals with types.
- Describe the local loading sequence and how `START_ORDER` quantity is used.
- Explain sensors, actuators, Plant behaviour, safety and pipeline synchronisation.
- Report implementation status, tests performed and remaining integration work.

### 9.2 Conveyor Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Conveyor Controller/Plant Clock Domains and ownership.
- Document bottle-detection, motor and neighbouring-machine interfaces.
- Describe start/stop behaviour and conflict or overload prevention.
- Explain how status polling remains independent of Conveyor operation.
- Provide implementation progress, test method and observed evidence.

### 9.3 Rotary Table Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Rotary Turntable Clock Domain(s) and local interfaces.
- Describe alignment detection, indexing behaviour and station synchronisation.
- Explain how rotation is authorised without colliding with other machine actions.
- Document Controller-to-Plant signals and status-state transitions.
- Provide implementation and testing evidence or identify remaining work.

### 9.4 Filler A Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define Filler A Clock Domain(s), Plant abstraction and signal ownership.
- Explain storage and application of `FILL_A_RATIO` for the active product.
- Document bottle-presence, dispensing and completion sensing.
- Describe coordination with Filler B and overflow prevention.
- Provide implementation status, tests and measured/simulated evidence.

### 9.5 Filler B Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define Filler B Clock Domain(s), Plant abstraction and signal ownership.
- Explain storage and application of `FILL_B_RATIO` for the active product.
- Document bottle-presence, dispensing and completion sensing.
- Describe coordination with Filler A and overflow prevention.
- Provide implementation status, tests and measured/simulated evidence.

### 9.6 Lid Loader Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Lid Loader Clock Domain(s) and relationship to the Lab 3 model.
- List current local sensors, actuators and external interfaces.
- Describe lid pickup, placement and confirmation behaviour.
- Explain synchronisation with the Rotary Turntable and Capper.
- Report modifications, implementation status and test results.

### 9.7 Capper Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Capper Clock Domain(s), Plant and ownership.
- Describe clamp, gripper, vertical movement and capping sequence abstractions.
- Document local sensor/actuator signals and fault handling.
- Explain why Capper completion is distinct from `BOTTLE_DONE` at unloading.
- Provide implementation progress and test evidence.

### 9.8 Bottle Unloader Controller and Plant

`[TEAM MEMBER TO COMPLETE]`

- Define the Unloader Clock Domain(s), collection mechanism and ownership.
- Describe detection and removal of a finished bottle from the output path.
- Explain when exactly one `BOTTLE_DONE` is emitted per collected bottle.
- Document status behaviour, local Plant interfaces and pipeline synchronisation.
- Provide implementation status, tests and evidence of duplicate prevention.

## 10. Plant-Level Integration and Production Sequence

`[TEAM TO COMPLETE AFTER MACHINE CONTROLLER/PLANT INTEGRATION]`

This section must be completed from the integrated production model rather than inferred from the Coordinator contract. The team should address:

- the bottle's movement from loading through Conveyor, Rotary Table, filling, lid placement, capping and unloading;
- local sensor and actuator interactions within every Controller/Plant pair;
- Controller-to-Controller events or shared conditions used to synchronise station handover;
- the permitted number and positions of multiple concurrent bottles;
- conflict, collision and overloading prevention;
- recovery or safe behaviour when expected sensor events do not occur; and
- the demonstrated production sequence for at least one complete batch and a multi-product order.

The final text should distinguish implemented behaviour from planned refinements and should refer to trace, terminal or visual evidence.

## 11. System Visualisation

The current GP integration provides a read-only Swing view of eight machine states and current product-batch progress. `ABSVisualisationPlantCD` receives the latest status of Bottle Loader, Conveyor, Rotary Turntable, Filler A, Filler B, Lid Loader, Capper and Bottle Unloader, together with required and completed bottle counts. These values originate at the Coordinator; the visualisation does not contact or control Machine Controllers directly.

This display supports system-level observation and integration debugging. More detailed symbolic representation or workpiece-position visualisation may be developed as an individual extension, but it should be described as GP functionality only after its interface and behaviour have been integrated and validated.

**Figure 3. ABS Visualisation**

`[INSERT VISUALISATION SCREENSHOT HERE]`

## 12. Testing and Validation

### 12.1 POS Validation

Repository evidence supports two implemented validation layers: form validation in `POSVisualisation.queueOrderFromForm()` and protocol validation in `OrderV1.parse()`. The framework-free `OrderV1SelfTest.java` defines checks for a valid two-product order, zero products, ratios that do not total 100, zero quantity and a product-count mismatch. It also exercises current-batch loading, two `BOTTLE_DONE` counts, next-product advancement and construction of the completion payload.

The repository documents `OrderV1SelfTest PASSED` as the expected output, but no retained execution log was identified during preparation of this draft. The team should run the test in the agreed Java 8/SystemJ environment and attach dated console evidence before claiming a verified pass in the submitted report.

### 12.2 Coordinator Integration Testing

The `tests/` directory contains a test-only `MockControllerCD`, `MockStateV1`, production-like and no-visualisation Coordinator mappings, and a documented multi-runtime procedure. The Mock consolidates all eight Coordinator-facing interfaces onto port 11002, replies to every status poll and simulates a final path from Capper through Conveyor output to Bottle Unloader. It emits `BOTTLE_DONE` only at the simulated collection stage. The documented expected status sequence is `READY -> BUSY -> DONE`, with progress moving from `0/2` to `1/2` and `2/2`.

This infrastructure is suitable for checking POS/Coordinator communication, recipe and quantity dispatch, polling/reply names, progress counting, the Visualisation path and the final `ORDER_COMPLETE` return. It is explicitly not evidence that real Machine Controllers or physical Plant models have been validated. As with the self-test, the procedure and expected evidence are present, but a retained execution transcript was not treated as available proof for this report draft.

### 12.3 Full Machine Integration

`[TEAM TO COMPLETE AFTER REAL MACHINE CONTROLLERS ARE INTEGRATED]`

Planned validation should include:

1. verify all production Clock Domain names, ports, signal names and SystemJ types against Interface Freeze V1;
2. show that each status request produces the current status without advancing a physical sequence;
3. test one valid and several invalid POS orders;
4. execute a complete single-product batch using real Controller/Plant pairs;
5. execute a multi-product order and confirm batch ordering and recipe changes;
6. demonstrate more than one bottle concurrently without collision or overload;
7. prove that one and only one `BOTTLE_DONE` is emitted for each collected bottle;
8. confirm Visualisation values agree with Controller states and collected-bottle counts; and
9. capture POS, Coordinator, Controller and visual evidence for the final demonstration.

## 13. Current Progress

| Component | Responsible Member | Current Status | Remaining Work |
| --- | --- | --- | --- |
| POS | Xuqi | Integration V1 Swing form, Order V1 encoding/validation, SystemJ `ORDER` output and completion display are present. | Re-run documented tests in the final team environment; obtain team review and final demonstration evidence. |
| Coordinator | Xuqi | Order reception, batch dispatch, polling, status storage, progress counting, multi-product advancement and delayed completion notification are present. | Integrate and validate against all real Machine Controllers; resolve any team-agreed interface changes consistently. |
| POS–Coordinator integration | Xuqi | Matching SystemJ declarations and XML mappings exist; integrated flowchart and Mock-based test procedure are available. | Capture reproducible run evidence with the current repository version and then repeat with real Controllers. |
| Coordinator–Controller interface | Xuqi | Frozen Integration V1 contract defines eight Controller boundaries, status codes, Clock Domains and receiver ports. | Each Controller owner must implement and demonstrate conformance; team must record any approved revisions. |
| Mock integration infrastructure | [USER / TEAM TO CONFIRM] | Test-only Mock, state support, XML mappings and documented expected evidence are present. | Confirm ownership; execute tests and retain logs. Do not substitute Mock results for real machine validation. |
| Bottle Loader Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Conveyor Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Rotary Turntable Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Filler A Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Filler B Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Lid Loader Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Capper Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |
| Bottle Unloader Controller/Plant | [TEAM MEMBER] | [TEAM MEMBER TO COMPLETE] | Implement, document and integrate. |

## 14. Task Allocation

| Task | Responsible Student | GP/IP | Status |
| --- | --- | --- | --- |
| POS implementation and POS/Coordinator order protocol | Xuqi | GP | Current Integration V1 implementation present; final team validation pending. |
| ABS Coordinator and Coordinator-facing interface | Xuqi | GP | Current Integration V1 implementation and frozen contract present; real Controller integration pending. |
| Bottle Loader Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Conveyor Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Rotary Turntable Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Filler A Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Filler B Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Lid Loader Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Capper Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Bottle Unloader Controller/Plant | [TEAM TO COMPLETE] | GP | [TEAM TO COMPLETE] |
| Integrated visualisation and screenshot evidence | [TEAM TO COMPLETE] | GP / integration | [TEAM TO COMPLETE] |
| Individual-project extensions and GP integration | [TEAM TO COMPLETE] | IP | [TEAM TO COMPLETE] |
| Final integrated test, demonstration and report evidence | [TEAM TO COMPLETE] | GP | Planned. |

Each student must identify clearly which designs, source modules, tests, figures and report sections constitute their GP contribution and which work belongs to their IP. The final allocation should be agreed by the team and consistent with peer-assessment records.

## 15. Design Decisions and Rationale

### 15.1 Central Coordinator

A central Coordinator provides one authoritative order and batch state. It accepts a purchase order once, selects the current product, distributes the quantity and recipe, observes completion counts and issues one logical order-completion result. This avoids duplicating purchase-order parsing across Machine Controllers. Local physical logic remains distributed, so the Coordinator does not become a monolithic actuator sequencer.

### 15.2 Polling Strategy

Approximately one-second polling gives the Coordinator a uniform method of obtaining current state from eight independently developed Controllers. Rate limiting is important because SystemJ logical reactions can execute much faster than the external communication layer should be queried. A common request/reply convention supports Coordinator awareness, visualisation and integration debugging. The design also makes the semantic rule explicit: polling reports state but never triggers physical progress.

The team should reassess the polling interval and fault/time-out policy during full integration. The present baseline records current values but does not by itself establish a complete production fault-recovery strategy.

### 15.3 Frozen Interfaces

Stable signal names, types, Clock Domain names and receiver ports allow machine owners to work in parallel. The Interface Freeze V1 documents the exact M1 boundary and separates it from local Plant or neighbouring-machine signals. Any revision must be applied consistently to `.sysj`, XML, tests, spreadsheet references and integration documentation, preventing subtle sender/receiver mismatches.

### 15.4 Separation of POS and Production Control

The POS captures customer intent and reports completion; it does not control machines. It emits one validated order to the Coordinator, which translates the order into batch-level configuration. This isolates user-interface concerns from production control, permits independent validation at both boundaries and prevents the POS from depending on Plant actuators or machine topology.

### 15.5 Other Team Decisions

`[TEAM TO COMPLETE]`

The team should document the rationale for Controller-to-Controller synchronisation, Plant abstraction level, multiple-bottle capacity, fault handling, two-filler coordination and any approved EABS extensions.

## 16. Remaining Work Before Final Integration

The current POS, Coordinator and frozen interface form an integration foundation. The following items remain team work rather than completed results:

- [ ] Confirm group metadata, task ownership and GP/IP boundaries.
- [ ] Complete every assigned Machine Controller and Plant model.
- [ ] Match all real Controller declarations and XML mappings to Interface Freeze V1.
- [ ] Integrate the real Controllers with `CoordinatorCD` one boundary at a time.
- [ ] Verify approximately one-second status polling and all eight real status replies.
- [ ] Define and test Controller-to-Controller production synchronisation.
- [ ] Validate Liquid A/Liquid B sequencing and overflow prevention.
- [ ] Demonstrate the complete physical sequence from Bottle Loader to Bottle Unloader.
- [ ] Demonstrate multiple bottles at different stations without conflict or overload.
- [ ] Test a multi-product purchase order and batch-to-batch recipe transition.
- [ ] Confirm exactly one `BOTTLE_DONE` for each collected bottle.
- [ ] Integrate the final visualisation and capture Figure 3.
- [ ] Insert the team conceptual architecture as Figure 1.
- [ ] Export and insert the integrated POS–Coordinator flowchart as Figure 2.
- [ ] Run and retain dated self-test, Mock integration and real-machine integration evidence.
- [ ] Complete all team-member sections, references, captions and repository/version metadata.
- [ ] Perform the full POS -> production -> completion demonstration.

## 17. Conclusion

The interim design establishes the main EABS/POS architecture and the key interfaces needed for parallel team development. The POS provides validated order entry and completion presentation, while the ABS Coordinator provides the system-level order, recipe, status and batch-progress orchestration foundation. The design intentionally keeps local machine sequences and Plant control within the individual Machine Controllers.

The frozen Coordinator-facing interface gives the remaining machine modules a concrete integration target. The next stage is to complete and document those Controller/Plant pairs, validate the multiple-bottle production sequence, replace Mock boundaries with real implementations and collect end-to-end evidence. The complete EABS should not be considered operational until that integration and validation work has been performed.

# References

1. Z. Salcic, *COMPSYS 704 Advanced Embedded Systems - Project 1: Designing Advanced Embedded Software Systems - SystemJ Approach*, revised 2026 project brief, University of Auckland, 2026.
2. H. Park and Z. Salcic, *Designing Software Systems Using SystemJ*, Embedded Systems Research Group, Department of Electrical, Computer and Software Engineering, University of Auckland, 2019. Listed as a course reading in the 2026 Project 1 brief.
3. A. Malik, Z. Salcic, P. S. Roop and A. Girault, “SystemJ: A GALS language for system level design,” *Computer Languages, Systems & Structures*, vol. 36, no. 4, pp. 317-344, 2010, doi:10.1016/j.cl.2010.01.001.

