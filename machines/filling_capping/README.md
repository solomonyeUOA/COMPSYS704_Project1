# M4 Filling, Capping and Two-Size Extension

This directory contains Member 4's implemented SystemJ Controller/Plant
modules and deterministic Java models. The implementation supports 200 mL
(`S`) and 500 mL (`L`) bottles, sequential Liquid A/B filling, geometry-aware
Filler/Capper positioning, and size-based sorting and packaging.

`member4_system.xml` is the canonical production mapping.
`member4_demo.xml` and `member4_demo_driver.sysj` are test-only.

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
| `BottleContextRegistryCD` | 11011 | `BOTTLE_RECOGNISED` |
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
M4 receivers and payload validation are implemented; the M2 peer remains the
current external dependency.

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

## Build and verify

From the repository root, replace the lab path if necessary:

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
  machines/filling_capping/member4_demo_driver.sysj

javac --release 8 -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  -d build/member4-classes \
  build/member4-generated/*.java machines/filling_capping/*.java

java -cp "build/member4-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  Member4ModelSelfTest
```

The deterministic self-test covers valid 200/500 mL cycles, formula results,
Filler B gating, overflow, recipe mismatch, geometry selection, duplicate
suppression, wrong-lane rejection and package counting. Expected output:

```text
Member4ModelSelfTest PASSED
```

Compile and run the deterministic M3/M4 boundary test with both model sets:

```sh
javac --release 8 -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
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
