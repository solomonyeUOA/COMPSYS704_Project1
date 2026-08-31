# Machine module integration locations

Before implementing a Controller, read:

- `../docs/INTERFACE_FREEZE_V1.md`
- `../docs/CONTROLLER_IMPLEMENTATION_GUIDE.md`

Coordinator-facing signal names, types, status codes, Clock Domains and ports
form the current development integration baseline. Machine implementations
remain owned by their respective members; this M1 branch must not invent or
silently replace missing peer Controller/Plant behaviour.

Expected module ownership:

```text
machines/
  bottle_loader/
  conveyor/
  rotary_turntable/
  filler_a/
  filler_b/
  lid_loader/
  capper/
  bottle_unloader/
```

Each module should keep its Controller and Plant separate, for example:

```text
machines/conveyor/
  controller.sysj
  controller.xml
  plant.sysj
  plant.xml
```

Conveyor and Rotary are independent modules. The current Coordinator expects
`ConveyorControllerCD:11009` with `CONVEYOR_STATUS_REQUEST` /
`CONVEYOR_STATUS`, and `RotaryTableControllerCD:11003` with
`ROTARY_STATUS_REQUEST` / `ROTARY_STATUS`. Do not recreate the obsolete
combined `TransportControllerCD` / `TRANSPORT_*` status boundary.

Do not put motor, valve, actuator or sensor signals in the Coordinator
interface. Do not copy the test-only unified Mock into a real machine module.
Keep generated Java files out of manual editing and source control.
