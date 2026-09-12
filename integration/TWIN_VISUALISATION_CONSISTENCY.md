# Twin / overview consistency - QA M2-13 and M2-14

## Cause and scope

Based on team main `4f5fefc` (13 September 2026). The prior overview inferred
symbolic cycles from aggregate Controller status changes and advanced them on
a local timer. Those status messages can skip short stages. Confirmed BottleTwins
could therefore be COMPLETE while symbolic bottles remained at Filler A or Lid.
This was a display discrepancy, not evidence that completed twins were premature.

The overview now consumes the same validated generation/sequence-tagged snapshot
as both M2 and M4 IP tabs. Confirmed bottle identities/stages reconcile immediately;
timers, raw GP counts and missed status edges cannot invent or delay twin completion.
No machine controller, actuator, recipe, GP completion protocol or XML wiring was
changed. The read-only status-only fallback remains available when no twin feed
has been observed. After connecting, an empty/reset snapshot cannot revive old
symbolic bottles.

## What the display means

- A module shows a bottle's last confirmed waypoint, or a newer linked active
  resource observation. The geometry is schematic, not a measured position.
- COMPLETE clears that bottle from upstream stages immediately. GP unloading is
  displayed separately: it cannot prove that Sort / Pack has completed.
- The overview counts the latest observed product batch; older unfinished
  bottles remain visible until their own completion. Twin tables retain all
  observed bottles in the current reset generation. Normal POS-generated
  `PO<number>-P<number>` batches are ordered numerically; generic standalone
  identifiers use deterministic lexical grouping, not an inferred timestamp.
- A resource row is one machine's latest observation, not a history row per bottle.
  B001 is replaced by B002 when that machine reports B002. M2 READY/IDLE clears the
  linked bottle to `-`; this is not loss of the BottleTwin record.
- `OBSERVED_*` means last confirmed completed operation. It is labelled as such,
  rather than presented as continuous active-machine telemetry. Current M4 twin
  integration confirms combined filling at Filler B, not each Filler A actuator
  stage. The display does not synthesize missing Filler A twin information.
- Both IP tabs display generation/sequence and the same evidence labels. Snapshot
  generation/order validation prevents old or duplicate packets reverting the view.
- Capper mechanism telemetry is applied only to the matching bottle identity.
  A previous S bottle cannot supply the geometry or arm stage for the next L
  bottle. A delayed BUSY report cannot rewind a confirmed CAPPED waypoint.

## Run and acceptance checks

Stop the old launcher's six runtimes with Ctrl+C before rebuilding the normal
`build/classes`, then run from the repository root:

```powershell
python tools\project.py test
python tools\project.py run --no-build
```

Open the M2 Digital Twin and M4 Two-size IP tabs. Submit two bottles, then another
order without reset. Completed bottles must not remain active at Filler A/Lid;
resource identities/versions must progress to the next bottle. Reset must clear
the old generation and permit fresh production.

Automated runtime check (choose an unused port offset):

```powershell
python tools\project.py run --no-build --port-offset 20000 --order 'PO0001|1|P1,S,60,40,2' --order-count 2 --duration 55 --expect-completions 2 --expect-workpieces 4 --expect-visual-completions 4
```

The overview expectation checks observed completion records, not a claim that
every intermediate frame was rendered. Real fast operations can legitimately
skip visible intermediate frames; they must never be replayed as current work.

Focused regressions cover immediate two-bottle completion without timer ticks,
second-bottle movement while the first is complete, sparse status evidence,
reset and stale snapshots, repeated batches, shared M2/M4 tables, read-only cells,
and per-resource B001-to-B002 transitions. Native Swing fixture PNGs are explicitly
labelled test fixtures, not live production screenshots.

## Verification performed on 13 September 2026

All code was built and tested in the ignored isolated copy
`build/twin-sync-qa-20260913` because the user's original six-runtime GUI was
already running against `build/classes`. Those original processes and their
classes were left untouched. Temporary test runtimes stopped automatically.

- Java 8 / all 14 pinned SystemJ JARs verified; **34 SystemJ and 120 handwritten
  Java sources compiled**. All **37 executable suites passed**, including the
  210-assertion twin-flow and 345-assertion Swing consistency regressions.
  Canonical XML validation: 29 clock domains, zero peer warnings.
- GUI repeat-order run: two orders of two S bottles, no reset; both orders
  completed, exactly four COMPLETE twins and four overview completion records.
  Actual GUI frame trace ended `real=2 visual=2 required=2` for the final batch.
  Evidence: `build/twin-sync-qa-20260913/build/runs/20260913-083319-153199`.
- Active reset run: old work cancelled, all three member ACKs reached POS,
  then a fresh order containing one S bottle and two L bottles completed.
  Exactly three COMPLETE twins, no rejected updates.
  Evidence: `build/twin-sync-qa-20260913/build/runs/20260913-083315-430153`.
- All six runtime stderr logs were empty in both live runs.
- Native Swing fixture renders were visually inspected for cleared upstream
  bottle icons and readable resource evidence labels. They are retained under
  `build/twin-sync-qa-20260913/build/fixtures` and are labelled as fixtures.

These results verify the integrated simulation and its read-only display, not
hardware performance or continuous actuator telemetry. This change is intended
for review on its own branch; it does not alter controller ownership or GP interfaces.
