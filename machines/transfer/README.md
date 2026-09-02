# Member 2: Entry/Exit Transfer and Digital Twin

This directory is the complete M2-owned implementation. It follows the final
interim workbook/report and the frozen M1/M3 receiver mappings on current
`main`. M4 source is not yet available, so M4 profile and Sort/Pack boundaries
are implemented and tested as contracts but still require the real M4 peer.

## M2-owned Clock Domains

| Clock Domain | Port | Responsibility |
| --- | ---: | --- |
| `BottleLoaderControllerCD` | 11002 | Batch admission, identity/profile latch and verified loading |
| `ConveyorControllerCD` | 11009 | Entry transfer and evidence-gated `LOAD_BOTTLE` |
| `BottleUnloaderControllerCD` | 11010 | Verified P6 removal and exclusive `BOTTLE_DONE` |
| `LabellerControllerCD` | 11013 | Mandatory P6 label application and verification |
| `BottleLoaderPlantCD` | 12002 | Loader actuator/sensor abstraction |
| `ConveyorPlantCD` | 12009 | Entry motor and P1 evidence abstraction |
| `BottleUnloaderPlantCD` | 12010 | Physical removal and empty-P6 evidence |
| `LabellerPlantCD` | 12013 | Label actuation and independent verification |
| `M2TransferFaultAdapterCD` | 13002 | Frozen M2/M3 V2.1 fault exchange |
| `DigitalTwinCD` | 14002 | Read-only workpiece/resource twin owner |
| `DigitalTwinViewerCD` | 14003 | Read-only console snapshot client |

The canonical runtime mapping is `member2_system.xml`.

## Frozen peer boundaries

- M1 sends `START_ORDER` and read-only Loader/Conveyor/Unloader status polls.
- M2 returns status values `0 IDLE`, `1 READY`, `2 BUSY`, `3 DONE`,
  `4 FAULT` without advancing any operation.
- Conveyor sends `LOAD_BOTTLE(bottleId)` to
  `RotaryTablePlantCD:12003` only after the P1 photo-eye, entry-clear,
  motor-stopped, rotary-aligned and P1-available evidence all match.
- Labeller receives `BOTTLE_AT_LABEL(bottleId)` at port 11013 and sends
  `MARK_LABELLED(bottleId)` only after matching `LABEL_VERIFIED` evidence.
- Unloader sends `P6_CLEAR(bottleId)` only after matching physical-removal and
  empty-P6 evidence.
- Unloader alone emits one bounded `BOTTLE_DONE` PRESENT window followed by an
  ABSENT reaction. It does not send retry copies.
- The fault adapter sends event/ACK/result to `FaultSupervisorCD:13003` and
  receives `TRANSFER_RECOVERY_REQUEST` on 13002. It sends an abstract local
  intent; only the Conveyor Controller can operate the motor.

## M4 contract implemented by M2

M2 validates and preserves this full context unchanged:

```text
bottleId|sizeCode|capacityMl|geometryProfileId|packagingProfileId
```

The only accepted profiles are:

```text
bottleId|S|200|GEOM_S|PACK_S
bottleId|L|500|GEOM_L|PACK_L
```

- `LOAD_PROFILE` enters `BottleLoaderControllerCD:11002`.
- `UNLOAD_PROFILE` enters `BottleUnloaderControllerCD:11010`.
- `BOTTLE_READY_FOR_SORT` leaves M2 for
  `SortPackControllerCD:11012` after verified removal.

`BOTTLE_READY_FOR_SORT` does not replace or duplicate `BOTTLE_DONE`.

## Digital Twin IP

`WorkpieceTwin` is the one stored representation of a physical bottle/product
instance. `BottleTwin` remains only a conceptual alias. `ResourceTwin` stores
machine state separately, so a bottle can move while the Loader, Conveyor,
Labeller and Unloader retain independent status/fault histories.

`DigitalTwinCD` owns both stores. It accepts normalized copies of confirmed
events and returns immutable snapshots. It has no actuator output and is not
in the Controller/Plant path. An illegal, duplicate, conflicting or stale
update is rejected without changing physical state.

Workpiece update adapter:

```text
V1|W|eventId|workpieceId|eventType|resourceId|details|eventTimeMillis
```

Resource update adapter:

```text
V1|R|eventId|resourceId|resourceType|linkedWorkpieceId|status|operation|fault|eventTimeMillis
```

For `CREATED`, `details` carries
`sizeCode,capacityMl,geometryProfileId,packagingProfileId`; otherwise `-` is
used when no detail is required.

## Source map

- `*_controller.sysj` / `*_plant.sysj`: M2 production Clock Domains.
- `m2_transfer_fault_adapter.sysj`: V2.1 adapter.
- `digital_twin.sysj` / `digital_twin_viewer.sysj`: read-only IP service/client.
- `*ControllerModelV1.java`: deterministic Controller state machines.
- `M2PlantStateV1.java`: deterministic high-level Plant model.
- `M2MachineStateV1.java`: SystemJ-facing state facade.
- `WorkpieceTwin.java`, `ResourceTwin.java`, `DigitalTwinStoreV1.java`: twin
  models and single-owner store.
- `M2TransferFault*V2_1.java`: frozen V2.1 payload/correlation implementation.
- `Member2*SelfTest.java`: framework-free deterministic tests.

Generated Java is a build artifact and must not be edited or committed.

## Build and verify (PowerShell)

From the repository root:

```powershell
$lib = 'D:\Auckland_University\COMPSYS_704\Lab\Lab3\COMPSYS704_Lab_3\lib'
$java = 'C:\Program Files\Java\jdk-17\bin\java.exe'
$javac = 'C:\Program Files\Java\jdk-17\bin\javac.exe'

New-Item -ItemType Directory -Force `
  build\member2-generated,build\member2-classes

$sysj = Get-ChildItem machines\transfer -Filter *.sysj |
  Select-Object -ExpandProperty FullName
& $java -cp "$lib\*" com.systemj.compiler.JavaPrettyPrinter `
  -d build\member2-generated --nojavac --silence @sysj

$generated = Get-ChildItem build\member2-generated -Filter *.java |
  Select-Object -ExpandProperty FullName
$m2Java = Get-ChildItem machines\transfer -Filter *.java |
  Select-Object -ExpandProperty FullName
& $javac --release 8 -cp "$lib\*" -d build\member2-classes `
  @generated @m2Java common\OptionalSimpleClient.java

$cp = "build\member2-classes;$lib\*"
& $java -cp $cp Member2ControllerSelfTest
& $java -cp $cp Member2PlantSelfTest
& $java -cp $cp Member2DigitalTwinSelfTest
& $java -cp $cp Member2FaultAdapterSelfTest
```

All four tests must print `PASSED`. The real M2/M3 model compatibility test
also uses M3's existing Java sources:

```powershell
New-Item -ItemType Directory -Force build\member2-member3
$m3Java = Get-ChildItem machines\rotary_lid -Filter *.java |
  Select-Object -ExpandProperty FullName
& $javac --release 8 -cp "$cp" -d build\member2-member3 `
  @m3Java integration\Member2Member3SelfTest.java
& $java -cp "build\member2-member3;$cp" Member2Member3SelfTest
```

That test must also print `PASSED`. Then run the project topology validator:

```powershell
python tools\validate_integration.py
```

Run the M2 runtime only after the required receiver peers are started:

```powershell
& $java '-Djava.awt.headless=true' -cp $cp `
  com.systemj.SystemJRunner machines\transfer\member2_system.xml
```

## Remaining cross-member gates

- M1 has not frozen `LABELLER_STATUS_REQUEST`, `LABELLER_STATUS` or
  `VIZ_LABELLER_STATUS`. M2 implements its proposed Controller-side pair, but
  does not modify M1 or its Visualisation.
- M4 must supply the real Registry, filling/capping and Sort/Pack runtime and
  confirm the already aligned full-context payload.
- The complete merged-runtime acceptance test still needs real M1, M2, M3 and
  M4 runtimes plus physical timing/calibration values.
