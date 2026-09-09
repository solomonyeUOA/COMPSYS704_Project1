# M1-M4 reset, labeller and twin integration draft

This work targets the reset contract in the local QA document
`COMPSYS704_Reset_Team_Modification_Instructions.docx`. During the original
inspection, team main `42317b9` built and passed its 20 regression suites, but
the live reset test stalled at `RESET_PENDING_EXTERNAL_ACK` with all three ACK
flags false. It did not yet connect actual bottle/resource twin rows to the M1
UI. This draft imports the fixes from `COMPSYS704_Project1_1` commits `4922537`
and `a179761` into the team repository for M1/M2/M3/M4 review.

The historical verification sections below describe runs in the independent
`github_1` checkout, not runs in this team checkout. Their local ignored log
paths are relative to `github_1`. Team-branch verification must be recorded
separately; neither simulation scope nor hardware limitations change on import.

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

From this repository root after `python tools/project.py test` (configure the
pinned toolchain paths as described in the root README if needed):

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

## Historical verification in github_1: 10 September 2026, commit 4922537

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
not physical hardware. The unmodified team baseline was separately verified
at `42317b9`; that baseline lacked the member ACK implementation now included
in this draft.

## Historical QA follow-up in github_1: 10 September 2026, commit a179761

The overall production view now contains ten clickable stations, including
the explicit finishing sequence **Lid -> Cap -> Label -> Bottle Unloader ->
Sort / Pack**. Labeller telemetry feeds its own station, and M4 Sort/Pack
status is forwarded through the Coordinator to a separate station. GP
unloading counts never manufacture a Sort/Pack completion. Symbolic animation
may lag the actual counters; confirmed bottle/resource records remain in the
Digital Twin tables. Starting a new batch replaces the prior symbolic view.

The reported two-order limit was not a POS submission cap. One defect left the
labeller DONE when its two completion outputs drained in reverse order.
Another class of stalls came from missed single-reaction commands, feedback,
and intermember handoffs. The labeller now rearms after both outputs drain,
regardless of order; its resource READY event matches that transition.
M2 uses bottle-correlated bounded PRESENT windows for all six handoffs and
its four machine actuation/context paths. Completed identities prevent
re-actuation, sensor results are retained, and label PASS/FAIL is latched at
physical completion. M1's simulation batch request also uses held windows.

Final-source verification in the independent `github_1` checkout:

- Full build: 34 SystemJ sources and 108 handwritten Java sources;
  **31 executable suites passed**, with 29 clock domains and zero wiring
  warnings. Fault evaluation remains 11/11 with zero tested unsafe outputs.
- Label/actuation regression: 528 assertions, including dropped windows,
  immutable delayed FAIL, duplicate commands and reset cancellation.
  Repeated-order model regression: 304 assertions for five three-bottle orders.
- Actual GUI: three L/500 mL bottles all reached COMPLETE, eight resources,
  zero rejected twin updates, one POS order completion, and three traced
  animation journeys through Sort/Pack. Evidence:
  `build/runs/20260910-034954-618693`. The saved logs were rechecked after the
  laptop reboot; this is live evidence, not a render fixture.
- Active mixed-order reset: matching M2/M3/M4 ACKs, cleared generation 2
  with W=0/R=0, cancelled PO0001, and fresh PO0002 completed with three
  COMPLETE S/L bottles. Evidence: `build/runs/20260910-035732-850023`.
- Five consecutive mixed S/L orders without reset: PO0001 through PO0005
  all completed; exactly 15 bottle twins reached COMPLETE, with eight
  resource records and zero rejected updates. Evidence:
  `build/runs/20260910-035851-730607` (115-second acceptance run).
- All six runtime stderr logs were empty in all three above live checks.
  Test processes were stopped afterward.

Earlier failed GUI runs were retained for diagnosis: they exposed lost
LOAD_BOTTLE, MARK_LABELLED and sort-ready pulses. They are not counted as
passing checks. No M3 logic change was needed for this follow-up. Reproduce
the GUI and repeated-order acceptance using the commands in the root README.

## Team integration branch verification (10 September 2026)

These checks were rerun in `D:\Auckland_University\COMPSYS_704\Project1\github`
on `integration/m1-m4-reset-twins-finishing`, based on team main `42317b9`.
The imported runtime source/XML matches the tested independent `a179761`;
the subsequent changes adapt documentation only.

- `python tools/project.py test`: 34 SystemJ sources, 108 handwritten Java
  sources, all 31 executable suites passed; 29 canonical clock domains,
  zero wiring warnings; fault evaluation 11/11 with zero tested unsafe outputs.
- Five consecutive mixed S/L orders without reset: PO0001 through PO0005
  completed, exactly 15 COMPLETE bottle twins, eight resource records and
  zero rejected updates. New evidence: `build/runs/20260910-042336-631346`.
- Reset during an active order: all three member ACKs reached the Coordinator
  and POS, generation 2 cleared to W=0/R=0, then fresh PO0002 completed with
  three COMPLETE mixed S/L bottles. New evidence:
  `build/runs/20260910-042252-736808`.
- All six stderr logs were empty in both new live runs. Their test processes
  stopped afterward. The user's existing `github_1` GUI was left running;
  isolated port offsets avoided interfering with it.

Exact live commands used after the full build:

```powershell
python tools\project.py run --no-build --headless --port-offset 30000 --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,2' --order-count 5 --duration 150 --expect-completions 5 --expect-workpieces 15
python tools\project.py run --no-build --headless --port-offset 20000 --order 'PO0001|2|P1,S,60,40,1;P2,L,50,50,2' --order-count 2 --reset-after 12 --duration 65 --expect-reset --expect-completions 1 --expect-workpieces 3
```

This supplements, rather than relabels, the historical GUI evidence above.
All four members should review their interfaces and reproduce the checks on
their own pinned toolchain before this draft is marked ready to merge.

## Limits

This is an integrated **simulation**, not hardware safety certification. M3
explicitly records removal of simulated bottles during reset. Real hardware
would need confirmed homing, sensing and operator reconciliation before ACK.
Transport retries are bounded; a disconnected member can still leave reset
pending, intentionally. Identity history currently persists within a running
JVM, not across a full six-runtime restart. Start/stop all six together. The
launcher never pushes either GitHub repository or stops unrelated services.

For a failed label verification, use POS **Reset System** and submit a fresh
order after the member ACK barrier completes. Clearing the injected verifier
fault must not turn an earlier FAIL into PASS. Same-bottle relabelling through
the low-level `resetLabellerFault` helper is not an integrated recovery path;
it would require a separately correlated new attempt and physical clearance.
