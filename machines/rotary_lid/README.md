# Member 3 Rotary and Lid Loader Controllers

This folder contains the Member 3 Controllers, Plant models, local integration,
visualisation and deterministic tests for the rotary table and lid loader.

## Scope

- `TransportControllerCD` represents the rotary/turntable status boundary from
  the frozen interface.
- `LidLoaderControllerCD` represents the lid loader status boundary from the
  frozen interface.

The controllers intentionally use only the frozen Coordinator-facing signals:

```text
TRANSPORT_STATUS_REQUEST -> TRANSPORT_STATUS
LID_STATUS_REQUEST       -> LID_STATUS
```

Status values follow Interface V1:

```text
0 IDLE
1 READY
2 BUSY
3 DONE
4 FAULT
```

## Files

```text
transport_controller.sysj
transport_controller.xml
rotary_table_plant.sysj
rotary_table_plant.xml
lid_loader_controller.sysj
lid_loader_controller.xml
lid_loader_plant.sysj
lid_loader_plant.xml
member3_system.xml
Member3MachineStateV1.java
Member3PlantStateV1.java
RotaryControllerModelV1.java
RotaryTablePlantModelV1.java
LidLoaderControllerModelV1.java
LidLoaderPlantModelV1.java
BottleStateV1.java
Member3Visualisation.java
```

## Integration Notes

The current Interface V1 does not provide Coordinator commands for individual
rotary or lid-loader operations. Coordinator polling is therefore read-only:
it reports the latest local model state and never causes a machine operation.

The local models implement the safety-relevant behaviour from the brief:

- the rotary table performs a 60-degree step, drives its motor for 500 ms,
  verifies alignment, blocks when a capped bottle occupies position 1, and
  enters `FAULT` on alignment timeout;
- the lid loader requires both a bottle and an available lid, waits for pick
  and placement confirmations, de-energises actuators on timeout, and requires
  the fault condition to be cleared before reset.

The SystemJ Controller and Plant clock-domains map local sensor/actuator
signals onto these Java models while keeping the frozen Coordinator interface
unchanged. `member3_system.xml` is the canonical all-in-one M3 runtime.

Detailed actuator, sensor and neighbouring-controller protocols remain local
to this module and need group agreement. They must not be added to the
Coordinator interface unless the team updates Interface V1.

## State Machines

```text
Rotary: READY -> ROTATING -> VERIFYING_ALIGNMENT -> DONE -> READY
                                      `-> FAULT -> READY (aligned reset)

Lid:    READY -> PICKING -> PLACING -> DONE -> READY
          `------ timeout/no lid ----> FAULT -> READY (lid restored)
```

## Self-test

Use the course Lab 3 `lib` directory. From the repository root on macOS/Linux:

```text
mkdir -p build/member3-generated build/member3-classes

java -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  com.systemj.compiler.JavaPrettyPrinter \
  -d build/member3-generated --nojavac --silence \
  machines/rotary_lid/transport_controller.sysj \
  machines/rotary_lid/rotary_table_plant.sysj \
  machines/rotary_lid/lid_loader_controller.sysj \
  machines/rotary_lid/lid_loader_plant.sysj

javac --release 8 -cp "/path/to/COMPSYS704_Lab_3/lib/*" \
  -d build/member3-classes \
  build/member3-generated/*.java machines/rotary_lid/*.java

java -cp "build/member3-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  Member3ControllerSelfTest
java -cp "build/member3-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  Member3PlantSelfTest
```

Expected outputs are `Member3ControllerSelfTest PASSED` and
`Member3PlantSelfTest PASSED`.

Compile `member3_demo_driver.sysj` with the four production `.sysj` files and
run `member3_demo.xml` for an automatic end-to-end SystemJ demonstration. The
terminal must finish the sequence with `MEMBER3 SYSTEMJ DEMO PASSED`.

Run the integrated M3 runtime after the Coordinator receiver is available:

```text
java -Djava.awt.headless=false \
  -cp "build/member3-classes:/path/to/COMPSYS704_Lab_3/lib/*" \
  com.systemj.SystemJRunner machines/rotary_lid/member3_system.xml
```

Use `-Djava.awt.headless=true` for terminal-only testing.

## Team boundary

Cross-member signals such as bottle loading, fill completion, cap completion
and exit removal are proposals, not frozen interfaces. See
`docs/MEMBER3_TEAM_INTERFACE_DRAFT.md` before integration with M2 and M4.
