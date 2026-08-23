# Machine modules

Before implementing a Controller, read:

- `../docs/INTERFACE_FREEZE_V1.md`
- `../docs/CONTROLLER_IMPLEMENTATION_GUIDE.md`

Coordinator-facing signal names, types, status codes, Clock Domains and ports
form the team-agreed Integration V1 baseline.

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

Do not put motor, valve, actuator or sensor signals in the Coordinator
interface. Do not copy the test-only unified Mock into a real machine module.
Keep generated Java files out of manual editing and source control.
