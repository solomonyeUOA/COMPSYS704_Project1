# Member 2: Entry/Exit Transfer and Digital Twin

This directory is the complete M2-owned implementation. It follows the final
interim workbook/report and the frozen M1/M3/M4 receiver mappings on current
`main`. The M2/M3 and M2/M4 boundaries retain their existing signal names,
payload types, IP addresses and ports.

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

In the independent `COMPSYS704_Project1_1` copy, M1 also polls the Labeller and
forwards its status to the visualisation. Whole-system reset and the live twin
view are integrated additions to the original group boundary.

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

## Reliable cross-Clock-Domain hand-offs

The event-valued M2 hand-offs `BOTTLE_AT_CONVEYOR`, `LOAD_BOTTLE`,
`MARK_LABELLED`, `UNLOAD_READY`, `P6_CLEAR` and `BOTTLE_READY_FOR_SORT` retain
one pending bottle payload and offer it as at most five 200 ms `PRESENT`
windows, separated by 100 ms `ABSENT` gaps. This lets independently scheduled
receivers observe a handoff even when individual reactions are missed.
Copies preserve the exact bottle ID and payload; the number of logical
windows remains bounded regardless of the producer's reaction rate.

The local M2 receivers acknowledge `BOTTLE_AT_CONVEYOR` and `UNLOAD_READY`
after accepting the matching bottle, which cancels their remaining copies.
The frozen M2/M3 and M2/M4 interfaces contain no acknowledgement for the other
events, so those offers stop after the bounded retry count. Receiver models
de-duplicate matching copies and reject conflicting payloads without repeating
physical work. The defaults can be adjusted for an integration experiment
with `m2.handoff.maximumOffers`, `m2.handoff.holdMillis` and
`m2.handoff.retryGapMillis` (the former `retryIntervalMillis` pulse setting
is no longer used);
production signal names, payloads, ports and receiver ownership are unchanged.

Loader, labeller and unloader commands/sensor confirmations, and conveyor
transfer context, use up to ten 100 ms PRESENT windows with 25 ms ABSENT gaps.
Canonical co-located Controller/Plant receivers acknowledge matching identities
after acceptance. Completed plant identities prevent repeat physical actions.
Label PASS/FAIL is latched at physical completion, not when its feedback is
eventually delivered. System reset cancels all pending offers and retires old
work. These are bounded retries, not a guarantee during a total connection loss.

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

M2 appends each immutable observation to a local ordered outbox as well as its
optional legacy telemetry output. Only DigitalTwinCD drains that outbox, so a
missed optional socket pulse cannot lose the initial CREATED context. Duplicate
copies cannot advance a twin twice. M3 LIDDED and M4 FILLED/CAPPED/SORTED
observations are buffered until the preceding workpiece stage is available.
UNLOADED is not completion: COMPLETE follows actual SORTED evidence.

Every 250 ms, DigitalTwinCD sends a complete replacement `VIZ_TWIN_SNAPSHOT`
to `ABSVisualisationPlantCD:11008`. The visualisation displays both BottleTwin
(the WorkpieceTwin store) and ResourceTwin tables, not just bottle counts.
M2 resource rows contain observed state, operation, linked bottle, fault and
version for Loader, Conveyor, Labeller and Unloader. Upstream resource rows
derived from confirmed milestones say `OBSERVED_FILLED`, `OBSERVED_LIDDED`,
`OBSERVED_CAPPED` or `OBSERVED_SORTED`; they describe the last confirmed
operation, not an inferred current actuator state.

The full wire form is:

```text
V2|TWIN|generation|sequence|W=n|R=n|REJECTED=n|WORKPIECES=id,stage,resource,version,size,capacity;...|RESOURCES=id,type,bottle,status,operation,fault,version;...
```

Each text cell is UTF-8 URL encoded. Startup generation is 0; after reset it
is the arbitrary-precision numeric RST suffix plus 1, so valid RST0000 is
distinct from startup. Snapshot sequence remains monotonic across resets.

## Whole-system reset

`M2_SYSTEM_RESET` enters M2TransferFaultAdapterCD:13002. Only `RST[0-9]{4,}`
identities are accepted. A new identity quarantines all bottle admission,
de-energises simulated actuators, clears pending feedback and then clears the
controllers, handoff retries, BOTTLE_DONE hold, active FT incident and both twin
stores. A later reaction verifies safe state before publishing the unchanged
resetId as `M2_SYSTEM_RESET_ACK` to CoordinatorCD:11001. ACK delivery uses ten
bounded retry pulses 100 ms apart. Duplicate reset IDs can repeat an ACK but
cannot clear newly admitted work; older IDs and alternate numeric spellings
are rejected.

Retired bottle/batch/event identities survive the reset. M2 event sequences
are never rewound and the transfer FT source epoch advances. START_ORDER must
first be ABSENT and consumes one admission window; a fresh LOAD_PROFILE is
also required before post-reset loading. This prevents a held START from
restarting a fast one-bottle loader batch. These are simulated actuator safety
checks; a hardware adapter must implement corresponding confirmed safe motion.

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
- `M2BoundedSignalOfferV1.java`: retained bounded retry-pulse transport.
- `WorkpieceTwin.java`, `ResourceTwin.java`, `DigitalTwinStoreV1.java`: twin
  models and single-owner store.
- `M2TransferFault*V2_1.java`: frozen V2.1 payload/correlation implementation.
- `Member2*SelfTest.java`: framework-free deterministic tests.

Generated Java is a build artifact and must not be edited or committed.

## Build and verify (PowerShell)

From the repository root:

```powershell
$lib = 'D:\Auckland_University\COMPSYS_704\Lab\Lab3\COMPSYS704_Lab_3\lib'
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot'
$java = "$javaHome\bin\java.exe"
$javac = "$javaHome\bin\javac.exe"

python tools\verify_project_toolchain.py `
  --java-home $javaHome --systemj-lib $lib

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
& $javac -cp "$lib\*" -d build\member2-classes `
  @generated @m2Java common\OptionalSimpleClient.java

$cp = "build\member2-classes;$lib\*"
& $java -cp $cp Member2ControllerSelfTest
& $java -cp $cp Member2PlantSelfTest
& $java -cp $cp Member2DigitalTwinSelfTest
& $java -cp $cp Member2FaultAdapterSelfTest
& $java -cp $cp Member2ReliableHandoffSelfTest
& $java -cp $cp Member2SystemResetSelfTest
& $java -cp $cp Member2LiveTwinSelfTest
```

All seven tests must print `PASSED`. The reliable hand-off test checks bounded
retry timing, mandatory `ABSENT` reactions, lost-pulse recovery and receiver
de-duplication. The real M2/M3 model compatibility test
also uses M3's existing Java sources:

```powershell
New-Item -ItemType Directory -Force build\member2-member3
$m3Java = Get-ChildItem machines\rotary_lid -Filter *.java |
  Select-Object -ExpandProperty FullName
& $javac -cp "$cp" -d build\member2-member3 `
  @m3Java integration\Member2Member3SelfTest.java
& $java -cp "build\member2-member3;$cp" Member2Member3SelfTest
```

That test must also print `PASSED`. Compile the real M4 Java models and the M4
compatibility test into the same classpath:

```powershell
$m4Java = Get-ChildItem machines\filling_capping -Filter *.java |
  Select-Object -ExpandProperty FullName
& $javac -cp "build\member2-member3;$cp" `
  -d build\member2-member3 @m4Java `
  integration\Member2Member4SelfTest.java
& $java -cp "build\member2-member3;$cp" Member2Member4SelfTest
```

This covers the quantity-one (`q1`) and quantity-three (`q3`) reliable
Sort/Pack scenarios. It deliberately drops the first transport pulse and
verifies that a retry is accepted exactly once. Then run the project
topology validator:

```powershell
python tools\validate_integration.py
```

Run the M2 runtime only after the required receiver peers are started:

```powershell
& $java '-Djava.awt.headless=true' -cp $cp `
  com.systemj.SystemJRunner machines\transfer\member2_system.xml
```

## Integration validation

- The independent project copy connects `LABELLER_STATUS_REQUEST`,
  `LABELLER_STATUS`, `VIZ_LABELLER_STATUS` and the read-only live twin feed.
  These additions do not change the original group GitHub repository.
- M4's real Registry, filling/capping and Sort/Pack runtime is now present and
  its receiver models align with M2's unchanged full-context payload. A live
  multi-runtime timing run is still required before final submission.
- The complete merged-runtime acceptance test still needs real M1, M2, M3 and
  M4 runtimes plus physical timing/calibration values.
