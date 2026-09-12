# Slow demonstration mode

Use this optional mode to watch the actual software Plant progress while
inspecting the overview and M2/M4 IP workpiece and resource tables.

```powershell
python tools\project.py run --demo-slowdown 5
```

`run-demo.bat` runs the same command. Normal `run-project.bat` is unchanged.
The multiplier is a finite number from 1 to 10 (fractional values are allowed).
Default 1 preserves normal timing. Use 10 for longer viewing, and restart to
change it; this is not a live speed slider. Rebuild after pulling changes before
using `--no-build`. Stop existing runs before rebuilding their shared classes.

## Timing and telemetry

Every runtime receives the same immutable JVM property
`-Dabs.simulation.slowdown=N` from the launcher. `SimulationTiming` multiplies
physical operation durations and their corresponding controller timeouts,
rounding fractional milliseconds up and rejecting invalid values or overflow.

| Area | Scaled | Left at original wall-clock timing |
| --- | --- | --- |
| M2 | Loader, conveyor, labeller and unloader Plant actions; conveyor arrival timeout | Retained commands, confirmations, handoffs, completion hold and reset |
| M3 | Rotary movement/alignment and lid pick/place actions and timeouts | Sensor hold, heartbeats, watchdog, transport and reset cancellation |
| M4 | Filler short/dose/refill, capper and sort/pack actions; operation timeouts | Recognition feed, transport, valve safe-stop and capper reset homing |
| M1 / visualization | No independent animation delay | Clock, IDs, snapshots, status polling, twin reconciliation and reset |

The POS and ABS titles identify demo mode. The Plant really completes later;
the UI does not replay old events after the twins have already completed.
BottleTwins still represent confirmed stages; ResourceTwins still have one
latest row per resource, not one row per bottle. Existing `OBSERVED_*` upstream
resources report last-completed work, not continuous live sensor state. This
mode does not add missing fine-grained Filler A/B telemetry.

Because transport and coordination remain real-time, overall order elapsed
time is not an exact multiple of normal elapsed time. `--duration`, automatic
order startup/inter-order delays, and `--reset-after` are wall-clock seconds
and do not scale. Give automated slow runs a longer duration.

This is software simulation pacing only. Do not use it as a hardware motor
speed or safety configuration.

## Reproducible checks

The default full suite stays at normal speed:

```powershell
python tools\project.py test
```

It includes Python launcher validation and Java timing, operation-boundary,
timeout and reset tests. Use separate JVMs to check the same timing self-tests
at 5 and 10, because the selected factor is immutable for the JVM lifetime.
Legacy normal-speed tests retain their existing exact millisecond assertions.
`test --demo-slowdown 5` is intentionally rejected.

Example two-bottle slow GUI acceptance, using the built classes:

```powershell
python tools\project.py run --no-build --demo-slowdown 5 --order 'PO0001|1|P1,S,60,40,2' --duration 150 --expect-completions 1 --expect-workpieces 2 --expect-visual-completions 2
```

Leave the windows open until the launcher stops its six child runtimes.
Inspect `build/runs/<timestamp>` for the POS completion, final twin states,
visual completion trace and runtime error logs. Use an unused `--port-offset`
if another simulation is running. Validation during development uses an
isolated source/build copy so existing interactive runs are not rebuilt.

## Validation recorded on 2026-09-13

- Full pinned-toolchain build: 34 SystemJ and 125 Java source files.
- All 41 Java executable suites and six Python launcher tests passed.
- The four dedicated timing suites passed in separate JVMs at factors 1,
  1.5, 5 and 10, including bounded timeout faults and immediate reset safety.
- Identical two-small-bottle orders completed in 8 seconds at normal speed
  and 42 seconds with factor 5 in this run. These are observations on this PC,
  not guaranteed performance ratios.
- The factor-5 GUI trace included both bottles through confirmed loading,
  filling, lidding, capping, labelling, unloading and completion. Final telemetry
  contained two COMPLETE workpieces, eight resource rows and zero rejected
  updates; resource identities advanced to the second bottle. Both overview
  completions were verified, and all six stderr logs were empty in each run.
- At factor 10, an active order was reset at 15 wall-clock seconds. All three
  members acknowledged reset, then a fresh mixed S/L order completed in 108
  seconds. Its two workpieces were COMPLETE, with eight resources, zero rejected
  updates and all six stderr logs empty. No heartbeat-watchdog intervention
  was observed in any of these three live runs.

Local evidence (ignored build output):
`build/slow-demo-qa-20260913/build/runs/20260913-095700-228969` (normal) and
`build/slow-demo-qa-20260913/build/runs/20260913-095656-939219` (factor-5 GUI).
The maximum-speed-multiplier reset check is in
`build/slow-demo-qa-20260913/build/runs/20260913-095817-864429`.

```powershell
python tools\project.py run --no-build --headless --demo-slowdown 10 --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,1' --order-count 2 --reset-after 15 --duration 150 --expect-reset --expect-completions 1 --expect-workpieces 2
```
