# Machine modules

Before implementing a Controller, read:

- `../docs/INTERFACE_FREEZE_V1.md`
- `../docs/CONTROLLER_IMPLEMENTATION_GUIDE.md`

Coordinator-facing signal names, types, status codes, Clock Domain names and
ports are frozen for Integration V1.

Each group member can add their machine module here, for example:

```text
machines/
  bottle_loader/
    controller.sysj
    controller.xml
    plant.sysj
    plant.xml

  turntable/
    controller.sysj
    controller.xml
    plant.sysj
    plant.xml

  filler/
    controller.sysj
    controller.xml
    plant.sysj
    plant.xml
```

Keep generated Java files out of manual editing.
Do not copy the test-only Mock Controller into a real machine module.
