# M4 Filling, Capping and Two-Size Extension

This directory contains Member 4's implemented SystemJ Controller/Plant
modules and deterministic Java models. The implementation supports 200 mL
(`S`) and 500 mL (`L`) bottles, sequential Liquid A/B filling, geometry-aware
Filler/Capper positioning, and size-based sorting and packaging.

`member4_system.xml` is the canonical production mapping.
`member4_demo.xml` and `member4_demo_driver.sysj` are test-only.
For the six-runtime group simulation, use `member4_simulation.xml` as the
M4 entrypoint. It contains the same ten M4 modules plus the finite,
output-only `RecognitionSimulatorCD` described below.

## Bottle context

Recognition produces:

```text
bottleId|sizeCode|capacityMl
```

`BottleContextRegistryCD` validates it and distributes one canonical context:

```text
bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId
```

The only supported pairs are:

| Size | Capacity | Geometry | Packaging |
| --- | ---: | --- | --- |
| `S` | 200 mL | `GEOM_S` | `PACK_S` |
| `L` | 500 mL | `GEOM_L` | `PACK_L` |

A size/capacity/profile mismatch is rejected. `bottleId` is retained through
every operation so stale, duplicate and wrong-bottle events cannot advance a
different bottle.

## Production boundaries

| Clock Domain | Receiver port | Main inputs |
| --- | ---: | --- |
| `FillerAControllerCD` | 11004 | `FILL_A_RATIO`, `FILLER_A_STATUS_REQUEST`, `BOTTLE_AT_FILL`, Plant feedback |
| `FillerBControllerCD` | 11005 | `FILL_B_RATIO`, `FILLER_B_STATUS_REQUEST`, `FILL_A_DONE`, Plant feedback |
| `CapperControllerCD` | 11007 | `CAPPER_STATUS_REQUEST`, `BOTTLE_AT_CAP`, Plant feedback |
| `BottleContextRegistryCD` | 11011 | `BOTTLE_RECOGNISED`, `M4_SYSTEM_RESET` |
| `SortPackControllerCD` | 11012 | `BOTTLE_READY_FOR_SORT`, Plant feedback |
| `FillerAPlantCD` | 12004 | Filler A commands / test fault injection |
| `FillerBPlantCD` | 12005 | Filler B commands / test fault injection |
| `CapperPlantCD` | 12007 | Capper commands / test fault injection |
| `RecognitionPlantCD` | 12011 | `RECOGNITION_REQUEST` |
| `SortPackPlantCD` | 12012 | Sort/Pack commands / test fault injection |

M3 sends the full canonical context as `BOTTLE_AT_FILL` at Position 2 and as
`BOTTLE_AT_CAP` at Position 4. M4 emits `MARK_FILLED(bottleId)` and
`MARK_CAPPED(bottleId)` to `RotaryTablePlantCD:12003` only after sensor-
confirmed safe completion. M4 never emits `BOTTLE_DONE`; that event remains
owned by the M2 Bottle Unloader after physical collection.

Registry `LOAD_PROFILE`/`UNLOAD_PROFILE` and downstream
`BOTTLE_READY_FOR_SORT` require the matching M2 integration endpoints. Their
M4 receivers and payload validation are implemented; end-to-end acceptance
uses the real M2 peers.

## Control and safety behaviour

- The batch recipe is stored as integer percentages from 0 to 100. A status
  request is read-only and never starts an actuator.
- Each filler computes `targetMl = capacityMl * ratio / 100`. For 60/40 this
  gives 120/80 mL for `S` and 300/200 mL for `L`.
- Filler B accepts only a matching, measured `FILL_A_DONE`; it cannot start
  before Filler A safely closes its valves and completes refill.
- `GEOM_S`/`GEOM_L` selects nozzle and Capper Z/clamp positioning before an
  operation begins.
- Valve interlocks, measured-volume checks, identity checks and per-stage
  timeouts enter `FAULT`, de-energise the Plant and suppress completion.
- Calibration remains explicit and configurable: `m4.toleranceMl`,
  `m4.shutoffLeadMl` and `m4.overflowMarginMl` default to zero until measured
  values are available. Filler B checks cumulative A+B volume, not only its
  local B dose.
- Capper completion requires the full clamp, lower, grip, twist, release,
  return-home, raise and unclamp feedback sequence.
- Sort/Pack selects `LANE_S`/`LANE_L`, confirms placement into
  `PACK_S`/`PACK_L`, and counts packages internally. It does not replace M2's
  unloading or `BOTTLE_DONE` responsibility.
- Completion and command transport uses bounded repeated copies with absent
  gaps for the course runtime; state models de-duplicate them by bottle and
  payload, so they represent one logical idempotent event.

## Whole-system reset and live twin observations

`BottleContextRegistryCD:11011` receives `M4_SYSTEM_RESET(resetId)`, accepting
only `RST[0-9]{4,}`. It first quarantines recognition, batch requests, contexts,
commands and feedback. Filler injector/inlet valves and dose movement stop;
Sort/Pack stops; the Capper stops gripping/twisting and confirms home, raised,
then unclamped in separate timed plant steps. Only after all safe-state checks
pass does `M4_SYSTEM_RESET_ACK(resetId)` go to `CoordinatorCD:11001`.

Reset cancels every pending command, feedback, context and twin transport
window. Registry bottle tombstones and the simulator's complete batch ledger
survive; bottles from retired batches, including not-yet-issued bottle IDs,
cannot revive. Requests arriving during quarantine are retired too. A duplicate
reset resends its ACK without clearing new work; older reset IDs are rejected.
The simulator returns to IDLE and accepts a new S/L batch with a new batch ID.
The actuator evidence is from the simulated plant; physical hardware still
requires its corresponding limit switches and safe-motion confirmation.

Actual Filler B, Capper and Sort/Pack completions queue `FILLED`, `CAPPED` and
`SORTED` workpiece observations. The registry sends them to
`DigitalTwinCD.M4_WORKPIECE_OBSERVATION:14002` using `OptionalSimpleClient`:

```text
V1|W|M4-E01-<sequence>|<bottleId>|<stage>|<resource>|-|<timestampMillis>
```

Resources are `FILLER_B`, `CAPPER` and `SORT_PACK`, respectively. Each queued
event has five identical copies separated by 50 ms, with stable event ID and
timestamp. A later completion cannot overwrite an earlier pending event.
The event sequence remains monotonic across system resets. These observations
describe actual completion evidence, independently of visualization animation.

`Member4SystemResetSelfTest` verifies actuator ordering before ACK, retirement
and quarantine, canceled offers, duplicate/stale resets, S/L restart, and
the complete queued observation stream across multiple resets.

## Build and verify

First use the frozen Temurin 8u502 toolchain and verify the JARs as described
in `../../toolchain/README.md`. From the repository root, replace the lab path
if necessary:

```sh
mkdir -p build/member4-generated build/member4-classes

java -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  com.systemj.compiler.JavaPrettyPrinter \
  -d build/member4-generated --nojavac --silence \
  machines/filling_capping/bottle_context_registry.sysj \
  machines/filling_capping/recognition_plant.sysj \
  machines/filling_capping/filler_a_controller.sysj \
  machines/filling_capping/filler_a_plant.sysj \
  machines/filling_capping/filler_b_controller.sysj \
  machines/filling_capping/filler_b_plant.sysj \
  machines/filling_capping/capper_controller.sysj \
  machines/filling_capping/capper_plant.sysj \
  machines/filling_capping/sort_pack_controller.sysj \
  machines/filling_capping/sort_pack_plant.sysj \
  machines/filling_capping/recognition_simulator.sysj \
  machines/filling_capping/member4_demo_driver.sysj

javac -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  -d build/member4-classes \
  build/member4-generated/*.java machines/filling_capping/*.java \
  common/OptionalSimpleClient.java

java -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  Member4ModelSelfTest
java -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  RecognitionSimulatorSelfTest
```

The deterministic self-test covers valid 200/500 mL cycles, formula results,
Filler B gating, overflow, recipe mismatch, geometry selection, duplicate
suppression, wrong-lane rejection and package counting. Expected output:

```text
Member4ModelSelfTest PASSED
RecognitionSimulatorSelfTest PASSED
```

Compile and run the deterministic M3/M4 boundary test with both model sets:

```sh
javac -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  -d build/member4-classes \
  machines/rotary_lid/*.java machines/filling_capping/*.java \
  machines/filling_capping/integration/*.java

java -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  Member3Member4IntegrationSelfTest
```

It must print `Member3Member4IntegrationSelfTest PASSED`; the test proves that
M3 forwards the unchanged S/L context at P2/P4 and accepts the matching M4
`MARK_FILLED`/`MARK_CAPPED` results.

Run the self-contained two-bottle SystemJ demonstration with:

```sh
java -Djava.awt.headless=true \
  -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  com.systemj.SystemJRunner machines/filling_capping/member4_demo.xml
```

It must finish with `MEMBER4 SYSTEMJ DEMO PASSED`. Run the production mapping
only when M1/M2/M3 receiver endpoints are available:

```sh
java -Djava.awt.headless=true \
  -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  com.systemj.SystemJRunner machines/filling_capping/member4_system.xml
```

Generated Java and class files are build artifacts and must not be committed.

## Six-runtime batch-driven simulation

`RecognitionSimulatorCD` supplies the environmental stimulus that a physical
camera/size sensor would provide. It is included only in
`member4_simulation.xml`. The canonical `member4_system.xml` still has no
automatic recognition source, although this draft adds reset/twin and status
integration to that XML. `RECOGNITION_REQUEST` remains internal to M4.

Use these two simulation mappings together:

```text
xuqi_coordinator/coordinator.xml
machines/filling_capping/member4_simulation.xml
```

When POS submits a product quantity and bottle size, M1 publishes
`M4_SIM_BATCH_REQUEST:String` on simulation-only port 11014:

```text
<orderId>-P<two-digit product index>|<quantity>|<sizeCode>
PO0001-P01|3|S
PO0002-P01|2|L
```

`sizeCode` is `S` or `L` only. Every batch owns its own size, so one run may
mix both bottle types, and the recognition request M4 generates for that
batch carries it as `<bottleId>|S` or `<bottleId>|L`.

M1 sends at most three identical copies, each PRESENT for 200 ms with a
600 ms ABSENT gap between copies. M4 accepts one logical batch
idempotently, generates `PO0001-P01-B001` through `PO0001-P01-B003`, then
waits for a different batch. The same ID with the same quantity and the same
size never restarts; the same ID with a different quantity **or** a different
size is a protocol conflict; a different ID cannot interleave while a batch is
active. A request whose size code is neither `S` nor `L` is `INVALID`.

The integrated receiver strictly rejects two-field `batchId|quantity`
requests. That form remains supported only by the standalone legacy Java
entry point, where the configured `m4.sim.size` supplies the size. Integrated
requests always carry exactly three fields, including `S` or `L`.

Launch M4 integrated simulation without quantity or size VM arguments:

```sh
java -Djava.awt.headless=true \
  -cp "build/member4-classes:/path/to/COMPSYS704_Project1_SystemJ_lib/*" \
  com.systemj.SystemJRunner machines/filling_capping/member4_simulation.xml
```

| VM property | Default | Integrated meaning |
| --- | --- | --- |
| `m4.sim.intervalMillis` | `1000` | Gap after one context's local copies drain |
| `m4.sim.requestGapMillis` | `100` | Minimum interval between request copies |
| `m4.sim.timeoutMillis` | `10000` | Maximum wait for local context distribution |

`m4.sim.size`, `m4.sim.quantity`, `m4.sim.bottleIdPrefix`, and
`m4.sim.startDelayMillis` are retained only by the standalone legacy Java
state-model entry point, where `m4.sim.size` still fixes one environmental
profile (`S` = 200 mL, `L` = 500 mL). Integrated `RecognitionSimulatorCD`
starts in `IDLE`, ignores all four, and takes the size from M1's batch
trigger.

Expected evidence for `PO0001-P01|3|S`:

```text
[M4-SIM] batch accepted id=PO0001-P01 quantity=3 size=S
[M4-SIM] recognising PO0001-P01-B001|S
[M4-SIM] context dispatched PO0001-P01-B001 1/3
...
[M4-SIM] FINISHED batch=PO0001-P01 quantity=3
```

The same order with `PO0002-P01|2|L` produces `PO0002-P01-B001|L` and the
500 mL geometry/packaging profiles instead.

`FINISHED` means the requested recognition contexts have been dispatched
locally. It is not an M2/M3 delivery acknowledgment or order-completion claim.
Do not run `member4_system.xml`, `member4_demo.xml`, and
`member4_simulation.xml` together because their M4 receiver ports overlap.
