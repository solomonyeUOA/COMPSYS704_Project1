# Independent reset, labeller and twin integration

This work targets the reset contract in the local QA document
`COMPSYS704_Reset_Team_Modification_Instructions.docx`. The original team main
at `42317b9` builds and passes its 20 regression suites, but the live reset test
stalls at `RESET_PENDING_EXTERNAL_ACK` with all three ACK flags false. It also
does not yet connect actual bottle/resource twin rows to the M1 UI. Fixes live
only in the independent `COMPSYS704_Project1_1` repository.

## Ownership and changes

| Owner | Implementation |
| --- | --- |
| M2 | `machines/transfer`: safe loader/conveyor/labeller/unloader reset; cancelled handoffs/feedback/completion; retained bottle/batch/event identities; fresh-profile and START rearming; workpiece + resource twins |
| M1 integration | `common/CoordinatorStateV1.java`, `xuqi_coordinator`, `visualisation`: labeller status wiring, stable recipe context, reset/order identity protections, read-only twin tables |
| M3 | `machines/rotary_lid`: reset ACK only after safe stop and explicit simulated bottle reconciliation; retained lid inventory/policy/history; actual LIDDED observations |
| M4 | `machines/filling_capping`: valves/movement off and staged capper HOME/RAISED/UNCLAMPED reset; stale-work fences; strict size-aware simulation batches; actual FILLED/CAPPED/SORTED observations |

The labeller's verification interlock remains in control: `MARK_LABELLED` and
unload admission follow successful `LABEL_VERIFIED`, not just arrival at P6.
Labeller status is now polled and shown in the overall UI as well as its resource
twin. Do not bypass label faults by marking a bottle complete.

## Twin contract

M2 `DigitalTwinCD:14002` is the snapshot owner. M2 local observations use an
ordered in-JVM queue in the canonical M2 runtime, avoiding lost single-tick
CREATED signals. M3/M4 observations are optional, read-only, bottle-correlated
socket telemetry with stable event IDs. Missing prerequisites are buffered;
the twin does not invent upstream stages. `COMPLETE` follows actual `SORTED`,
not unloader removal alone. GP POS completion still uses its frozen
`BOTTLE_DONE` interface, so it can precede final sort confirmation in the twin.

Every 250 ms, the owner sends a complete read-only `VIZ_TWIN_SNAPSHOT` to
`ABSVisualisationPlantCD:11008`:

```text
V2|TWIN|generation|sequence|W=n|R=n|REJECTED=n|WORKPIECES=...|RESOURCES=...
workpiece row: id,stage,resource,version,size,capacity
resource row: id,type,bottle,status,operation,fault,version
```

Rows use semicolons; string cells use UTF-8 URL encoding. Sequence is monotonic.
Generation is 0 before reset, then numeric reset ID + 1 (RST0000 -> 1), allowing
every valid reset identity to separate old data. The view validates row counts,
size/capacity, status codes and ordering; stale snapshots cannot repopulate a
reset display. Twin maps/queues are cleared, but retired identities survive.
M2 resource rows cover loader, conveyor, labeller and unloader. Additional
upstream resource rows say `OBSERVED_FILLED/LIDDED/CAPPED/SORTED`: they are
historical confirmed operations, **not** continuous BUSY/fault telemetry for
all upstream actuators.

## Reproduce live checks

From the independent repository after `python tools/project.py test`:

```powershell
# Small bottle; actual POS completion and both kinds of twins required.
python tools\project.py run --no-build --headless --order 'PO0001|1|P1,S,60,40,1' --duration 50 --expect-completions 1 --expect-twins

# S then L within one order; allow time for the full machine cycle.
python tools\project.py run --no-build --headless --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,1' --duration 75 --expect-completions 1 --expect-twins

# Reset during active work, then POS submits a new monotonic order ID.
python tools\project.py run --no-build --headless --order 'PO0001|1|P1,L,60,40,1' --order-count 2 --reset-after 12 --duration 65 --expect-reset --expect-completions 1 --expect-twins
```

These assertions fail on missing completion/ACKs/completed twin displays, rather
than treating six surviving JVMs as a pass. Inspect `[VIZ-TWIN-DATA]` in the
visualization log for confirmed stages and resource records, and check the
post-reset empty generation before new work. Regression tests additionally
cover duplicate/old resets, stale batches/bottles/events, S/L profiles, queued
observation ordering and unsafe reset states.

To run the synchronized original checkout without modifying its tracked files:

```powershell
python D:\Auckland_University\COMPSYS_704\Project1\github_1\tools\project.py run --repo-root D:\Auckland_University\COMPSYS_704\Project1\github
```

That command deliberately runs original behavior, including its incomplete
member reset receivers; use `github_1` for the independent fixes.

## Verified on 10 September 2026

- Full build: 34 SystemJ sources and 103 handwritten Java sources, Java 8 with
  all 14 pinned SystemJ JAR checksums verified.
- All **27 executable regression suites** passed; canonical wiring validation
  passed for 29 clock domains with zero warnings. Fault evaluation: 11/11
  scenarios, zero unsafe outputs in the tested model scenarios.
- Real GUI run: a small bottle completed and its twin reached COMPLETE;
  `build/runs/20260910-025018-429057`.
- Mixed order: one S/200 mL and two L/500 mL bottles all reached COMPLETE,
  with eight resource records and zero rejected twin updates;
  `build/runs/20260910-024840-685656`.
- Active L-order reset: matching M2/M3/M4 ACKs reached POS; generation 2
  displayed W=0/R=0; old PO0001 cancelled; fresh PO0002 completed;
  `build/runs/20260910-024825-051774`.
- Active reset followed by a fresh mixed S/L multi-product order: three new
  bottles reached COMPLETE, eight resource records, zero rejected updates;
  `build/runs/20260910-025223-679287`.
- Idle reset: all three ACKs and POS reset completion, zero orders created;
  `build/runs/20260910-025226-408151`.
- All six stderr logs were empty in every above live run. Test processes were
  stopped afterward. Expected duplicate handoff rejections can appear in
  stdout after a bottle has already moved; these do not create extra work.
- Actual Swing workpiece/resource table models are tested headlessly on the
  EDT for read-only data, reset clearing and stale snapshots. Native render
  fixtures under `build/ui-check` are labelled as test fixtures, not live data.

Run directories are local ignored evidence, not committed binaries/logs. The
commands above reproduce them. These checks validate the simulation scope,
not physical hardware. The unmodified team checkout was separately verified
at `42317b9`; its reset still lacks the member ACK implementation.

## Limits

This is an integrated **simulation**, not hardware safety certification. M3
explicitly records removal of simulated bottles during reset. Real hardware
would need confirmed homing, sensing and operator reconciliation before ACK.
Transport retries are bounded; a disconnected member can still leave reset
pending, intentionally. Identity history currently persists within a running
JVM, not across a full six-runtime restart. Start/stop all six together. The
launcher never pushes either GitHub repository or stops unrelated services.
