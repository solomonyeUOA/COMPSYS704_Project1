/** Deterministic snapshot fixtures; no control commands or live hardware claims. */
public final class ABSVisualisationTwinFlowSelfTest {
    private static int assertions;
    private static final String B1 = "PO0001-P01-B001";
    private static final String B2 = "PO0001-P01-B002";

    public static void main(String[] args) {
        fastCompletionNeedsNoEdgesOrTicks();
        secondBottleAndIdleResourceIdentity();
        stageEvidenceDoesNotInventAnimation();
        repeatedOrdersAndProducts();
        overlappingBatchesKeepEarlierUnfinishedBottle();
        p6IsLabellerInputNotLabelCompletion();
        resetsAndSequenceOrdering();
        resetSignalMayFollowFreshSnapshot();
        faultsAndImmutableSnapshots();
        completionRequiresComplete();
        System.out.println("ABSVisualisationTwinFlowSelfTest PASS (" + assertions + " assertions)");
    }

    private static void fastCompletionNeedsNoEdgesOrTicks() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(2);
        flow.acceptStatus(ABSVisualisationFlowModel.FILLER_A, 2);
        flow.acceptCompleted(2);
        flow.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "COMPLETE") + ";" + bottle(B2, "COMPLETE"),
            resource("FILLER_A", "FILLER_A", B1, 2, "FILLING")));
        check(flow.getSnapshot().isTwinDriven(), "snapshot enables authoritative mode");
        check(flow.getSnapshot().getTwinCompleted() == 2, "both completions immediate, without a timer");
        check(flow.getSnapshot().getTwinBottleCount() == 2, "exactly two identities");
        check("PO0001-P01".equals(flow.getSnapshot().getTwinBatchKey()), "real batch key");
        check(B2.equals(flow.getSnapshot().getBottles().get(1).getBottleKey()), "real second bottle key");
        assertNoModuleBottle(flow);
        for (int i = 0; i < 400; i++) {
            flow.acceptStatus(i % ABSVisualisationFlowModel.MODULE_COUNT, i % 4);
            flow.tick();
        }
        assertNoModuleBottle(flow);
        check(flow.getSnapshot().getTwinCompleted() == 2, "late statuses cannot replay completed work");
        flow.acceptStatus(ABSVisualisationFlowModel.FILLER_A, ABSVisualisationFlowModel.FAULT_STATUS);
        check("TWIN BATCH COMPLETE".equals(flow.getModeName()), "stale upstream fault does not hide completed twins");

        ABSVisualisationFlowModel noGp = new ABSVisualisationFlowModel();
        noGp.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "COMPLETE") + ";" + bottle(B2, "COMPLETE"), ""));
        check(noGp.getSnapshot().getRealCompleted() == 0, "GP count can lag telemetry");
        check(noGp.getVisualCompleted() == 2, "GP lag cannot hide actual twin completion");
        check(noGp.invariantsHold(), "independent completion counts preserve twin invariants");
    }

    private static void secondBottleAndIdleResourceIdentity() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(2);
        String bottles = bottle(B1, "COMPLETE") + ";" + bottle(B2, "LOADED");
        flow.acceptTwinSnapshot(snapshot(1, 1, bottles,
            resource("CONVEYOR-1", "CONVEYOR", B2, 2, "TRANSFER") + ";" +
            resource("LABELLER-1", "LABELLER", B1, 0, "IDLE")));
        ABSVisualisationFlowModel.ModuleSnapshot conveyor = flow.getModuleSnapshot(ABSVisualisationFlowModel.CONVEYOR);
        check(B2.equals(conveyor.getCurrentBottleKey()), "BUSY conveyor shows the second bottle, not the first");
        check(conveyor.isRunning(), "current resource BUSY is visible");
        check(flow.getModuleSnapshot(ABSVisualisationFlowModel.LABELLER).getCurrentBottleKey().isEmpty(),
            "IDLE last bottle is not current work");
        check(flow.getSnapshot().getTwinCompleted() == 1, "only confirmed completed bottle counted");

        // A stale BUSY record must not place the same bottle back upstream.
        flow.acceptTwinSnapshot(snapshot(1, 2, bottle(B1, "COMPLETE") + ";" + bottle(B2, "LABELLED"),
            resource("CONVEYOR-1", "CONVEYOR", B2, 2, "TRANSFER")));
        check(flow.getModuleSnapshot(ABSVisualisationFlowModel.CONVEYOR).getCurrentBottleKey().isEmpty(),
            "downstream twin overrides older upstream BUSY identity");
        check(B2.equals(flow.getModuleSnapshot(ABSVisualisationFlowModel.LABELLER).getCurrentBottleKey()),
            "second bottle reaches its own confirmed labeller waypoint");
        check(!flow.getModuleSnapshot(ABSVisualisationFlowModel.LABELLER).isRunning(),
            "LABELLED confirmation is not active labelling");
        check(flow.invariantsHold(), "no bottle appears in two modules");
    }

    private static void stageEvidenceDoesNotInventAnimation() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(2);
        flow.acceptCompleted(2);
        flow.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "COMPLETE") + ";" + bottle(B2, "FILLED"),
            resource("FILLER", "FILLER", B2, 3, "OBSERVED_FILLED")));
        ABSVisualisationFlowModel.ModuleSnapshot filled = flow.getModuleSnapshot(ABSVisualisationFlowModel.FILLER_B);
        check(B2.equals(filled.getCurrentBottleKey()), "combined FILLED uses confirmed Filler B waypoint");
        check(filled.getLifecycle() == ABSVisualisationFlowModel.ModuleLifecycle.HOLDING,
            "completed upstream observation is a waiting waypoint");
        check(!filled.isRunning(), "OBSERVED_FILLED never claims live filling");
        check(filled.getLiquidALevel() == 0.0 && filled.getLiquidBLevel() == 0.0,
            "no invented recipe split or measured fill level");
        check(flow.getModeName().contains("AWAITING TWIN"), "GP ahead of twin is explicit telemetry wait");
        long version = flow.getSnapshot().getVersion();
        for (int i = 0; i < 200; i++) flow.tickElapsed(1000000000L + i * 30000000L);
        check(flow.getSnapshot().getVersion() == version, "ticks never replay or drift live state");
        check(flow.getSnapshot().getTwinCompleted() == 1, "GP completion cannot fabricate final twin event");

        flow.acceptTwinSnapshot(snapshot(1, 2, bottle(B1, "COMPLETE") + ";" + bottle(B2, "COMPLETE"), ""));
        assertNoModuleBottle(flow);
        check(flow.getSnapshot().getTwinCompleted() == 2, "skipped intermediate snapshots reconcile immediately");
    }

    private static void repeatedOrdersAndProducts() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(2);
        String first = bottle(B1, "COMPLETE") + ";" + bottle(B2, "COMPLETE");
        flow.acceptTwinSnapshot(snapshot(1, 1, first, ""));
        int oldBatch = flow.getSnapshot().getBatchGeneration();
        String nextKey = "PO0002-P01-B001";
        flow.acceptTwinSnapshot(snapshot(1, 2, first + ";" + bottle(nextKey, "CREATED"), ""));
        check(flow.getSnapshot().getBatchGeneration() > oldBatch, "same-quantity new order gets new identity batch");
        check(flow.getSnapshot().getTwinCompleted() == 0, "old order completion is not new order completion");
        check(flow.getSnapshot().getTwinBottleCount() == 1, "new batch has one observed bottle, no invented second");
        check(nextKey.equals(flow.getSnapshot().getBottles().get(0).getBottleKey()), "new order identity preserved");
        check(flow.getSnapshot().getRequired() == 2, "GP expected quantity can exceed observed identities");
        String product2 = "PO0002-P02-B001";
        flow.acceptTwinSnapshot(snapshot(1, 3, first + ";" + bottle(nextKey, "COMPLETE") + ";" + bottle(product2, "P1"), ""));
        check("PO0002-P02".equals(flow.getSnapshot().getTwinBatchKey()), "latest product batch selected");
        flow.acceptTwinSnapshot(snapshot(1, 4, bottle("PO0100-P01-B001", "P1") + ";" +
            bottle("PO0099-P01-B001", "COMPLETE"), ""));
        check("PO0100-P01".equals(flow.getSnapshot().getTwinBatchKey()), "numeric order comparison, not row position");
        check(flow.invariantsHold(), "repeated batch invariants");

        ABSVisualisationFlowModel smaller = new ABSVisualisationFlowModel();
        smaller.acceptRequired(3);
        smaller.acceptTwinSnapshot(snapshot(1, 1, first + ";" + bottle("PO0001-P01-B003", "COMPLETE"), ""));
        smaller.acceptRequired(2);
        smaller.acceptTwinSnapshot(snapshot(1, 2, first + ";" + bottle(nextKey, "COMPLETE") + ";" +
            bottle("PO0002-P01-B002", "COMPLETE"), ""));
        check(smaller.getSnapshot().getRequired() == 2, "smaller next batch does not inherit old observed quantity");
        check("TWIN BATCH COMPLETE".equals(smaller.getModeName()), "new smaller batch can complete");
    }

    private static void overlappingBatchesKeepEarlierUnfinishedBottle() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(1);
        String next = "PO0001-P02-B001";
        flow.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "UNLOADED"), ""));
        int originalId = flow.getModuleSnapshot(ABSVisualisationFlowModel.UNLOADER).getCurrentBottleId();
        flow.acceptTwinSnapshot(snapshot(1, 2, bottle(B1, "UNLOADED") + ";" + bottle(next, "CREATED"), ""));
        check(flow.getSnapshot().getTwinBottleCount() == 1, "latest-batch count excludes previous unfinished bottle");
        check(flow.getSnapshot().getTwinCompleted() == 0, "latest-batch completion is independent");
        check(flow.getSnapshot().getBottles().size() == 2, "older unfinished bottle remains observable");
        check(B1.equals(flow.getModuleSnapshot(ABSVisualisationFlowModel.UNLOADER).getCurrentBottleKey()),
            "old bottle does not disappear before sort telemetry arrives");
        check(originalId == flow.getModuleSnapshot(ABSVisualisationFlowModel.UNLOADER).getCurrentBottleId(),
            "old schematic identity remains stable across batch switch");
        check(flow.getSnapshot().getRequired() == 1, "older in-flight bottle does not enlarge current expected count");
        check(flow.invariantsHold(), "overlapping batch invariants");
        flow.acceptTwinSnapshot(snapshot(1, 3, bottle(B1, "UNLOADED") + ";" + bottle(next, "COMPLETE"), ""));
        check(flow.getModeName().contains("EARLIER TWIN EVENTS PENDING"), "latest completion does not hide earlier unfinished work");
        flow.acceptTwinSnapshot(snapshot(1, 4, bottle(B1, "COMPLETE") + ";" + bottle(next, "COMPLETE"), ""));
        check(flow.getSnapshot().getBottles().size() == 1, "old completed history is retired from schematic");
        check(flow.getSnapshot().getTwinCompleted() == 1, "old completion is not counted in new product batch");
        assertNoModuleBottle(flow);
    }

    private static void p6IsLabellerInputNotLabelCompletion() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptTwinSnapshot(snapshot(1, 1, B1 + ",P6,LABELLER-1,7,S,200", ""));
        check(B1.equals(flow.getModuleSnapshot(ABSVisualisationFlowModel.LABELLER).getCurrentBottleKey()),
            "P6 bottle is shown at the observed labeller input");
        check(flow.getModuleSnapshot(ABSVisualisationFlowModel.CAPPER).getCurrentBottleKey().isEmpty(),
            "P6 bottle does not remain at capper");
        check(!flow.isModuleMoving(ABSVisualisationFlowModel.LABELLER), "P6 alone cannot claim active or completed labelling");
        check("P6".equals(flow.getSnapshot().getBottles().get(0).getTwinStage()), "P6 evidence remains distinct from LABELLED");
        flow.acceptTwinSnapshot(snapshot(1, 2, B1 + ",P6,LABELLER-1,7,S,200",
            resource("LABELLER-1", "LABELLER", B1, 2, "LABEL_APPLY")));
        check(flow.isModuleMoving(ABSVisualisationFlowModel.LABELLER), "identified BUSY labeller may process P6 input");
        flow.acceptTwinSnapshot(snapshot(1, 3, bottle(B1, "LABELLED"),
            resource("LABELLER-1", "LABELLER", B1, 2, "LABEL_APPLY")));
        check(!flow.isModuleMoving(ABSVisualisationFlowModel.LABELLER), "LABELLED confirmation overrides older same-station BUSY");
    }

    private static void resetsAndSequenceOrdering() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        ABSLiveTwinModel.Snapshot first = snapshot(1, 1, bottle(B1, "COMPLETE"), "");
        flow.acceptTwinSnapshot(first);
        long version = flow.getSnapshot().getVersion();
        flow.acceptTwinSnapshot(first);
        check(flow.getSnapshot().getVersion() == version, "duplicate complete snapshot is a no-op");
        flow.acceptTwinSnapshot(snapshot(1, 0, bottle(B1, "LOADED"), ""));
        check(flow.getSnapshot().getVersion() == version, "older snapshot sequence cannot regress stages");
        flow.resetSystem();
        flow.acceptTwinSnapshot(snapshot(1, 999, bottle(B1, "COMPLETE"), ""));
        check(flow.getSnapshot().getTwinBottleCount() == 0, "reset rejects previous generation, even with larger sequence");
        check(flow.getSnapshot().isTwinDriven(), "reset does not return to potentially stale status replay");
        flow.acceptTwinSnapshot(snapshot(2, 1, "", ""));
        check(flow.getSnapshot().getTwinCompleted() == 0, "empty fresh generation is visibly empty");
        flow.acceptRequired(1);
        flow.acceptTwinSnapshot(snapshot(2, 2, bottle("PO0003-P01-B001", "CREATED"), ""));
        check(flow.getSnapshot().getTwinBottleCount() == 1, "fresh generation accepts new production");
        check(flow.invariantsHold(), "reset invariants");
    }

    private static void faultsAndImmutableSnapshots() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "P1"),
            resource("FILLER_A", "FILLER_A", B1, 2, "FILLING")));
        ABSVisualisationFlowModel.FlowSnapshot beforeFault = flow.getSnapshot();
        check(beforeFault.getModule(ABSVisualisationFlowModel.FILLER_A).isRunning(), "identified resource can anchor filling");
        flow.acceptStatus(ABSVisualisationFlowModel.FILLER_A, ABSVisualisationFlowModel.FAULT_STATUS);
        check(!flow.getModuleSnapshot(ABSVisualisationFlowModel.FILLER_A).isRunning(), "fault freezes current resource");
        check(flow.getSnapshot().getTwinCompleted() == 0, "fault does not finish bottle");
        check(beforeFault.getModule(ABSVisualisationFlowModel.FILLER_A).isRunning(), "old snapshot is immutable");
        flow.acceptTwinSnapshot(snapshot(1, 2, B1 + ",FAULT,LABELLER-1,10,S,200", ""));
        check(flow.getModuleSnapshot(ABSVisualisationFlowModel.LABELLER).getLifecycle() ==
            ABSVisualisationFlowModel.ModuleLifecycle.FAULTED, "workpiece FAULT is shown at its observed resource");
        check(flow.invariantsHold(), "fault invariants");
    }

    private static void resetSignalMayFollowFreshSnapshot() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptTwinSnapshot(snapshot(2, 1, bottle("PO0003-P01-B001", "P1"), ""));
        flow.resetSystem("RST0001");
        check(flow.getSnapshot().getTwinBottleCount() == 1, "delayed reset preserves already observed fresh generation");
        flow.acceptTwinSnapshot(snapshot(2, 2, bottle("PO0003-P01-B001", "COMPLETE"), ""));
        check(flow.getSnapshot().getTwinCompleted() == 1, "same valid generation accepted after delayed reset");
        long version = flow.getSnapshot().getVersion();
        flow.resetSystem("RST0001");
        check(flow.getSnapshot().getVersion() == version, "duplicate reset ID is idempotent");
        flow.acceptTwinSnapshot(snapshot(1, 999, bottle(B1, "COMPLETE"), ""));
        check("PO0003-P01".equals(flow.getSnapshot().getTwinBatchKey()), "delayed reset never reopens old generation");
        check(flow.invariantsHold(), "reset signal race invariants");
    }

    private static void completionRequiresComplete() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        flow.acceptRequired(1);
        flow.acceptCompleted(1);
        flow.acceptTwinSnapshot(snapshot(1, 1, bottle(B1, "SORTED"), ""));
        check(flow.getSnapshot().getTwinCompleted() == 0, "SORTED is not fabricated COMPLETE");
        check(B1.equals(flow.getModuleSnapshot(ABSVisualisationFlowModel.SORT_PACK).getCurrentBottleKey()),
            "SORTED holds at sort/pack until COMPLETE snapshot");
        check(!flow.isModuleMoving(ABSVisualisationFlowModel.SORT_PACK), "confirmed sorted state does not imply continued sorting");
        flow.acceptTwinSnapshot(snapshot(1, 2, bottle(B1, "COMPLETE"), ""));
        assertNoModuleBottle(flow);
        check(flow.invariantsHold(), "completed invariant");
    }

    private static String bottle(String id, String stage) {
        return id + "," + stage + ",TEST-RESOURCE,11,S,200";
    }

    private static String resource(String id, String type, String bottle, int state, String operation) {
        return id + "," + type + "," + bottle + "," + state + "," + operation + ",-,2";
    }

    private static ABSLiveTwinModel.Snapshot snapshot(int generation, long sequence, String bottles, String resources) {
        ABSLiveTwinModel reader = new ABSLiveTwinModel();
        String payload = "V2|TWIN|" + generation + "|" + sequence + "|W=" + count(bottles) +
            "|R=" + count(resources) + "|REJECTED=0|WORKPIECES=" + bottles + "|RESOURCES=" + resources;
        check(reader.accept(payload), "valid snapshot fixture");
        return reader.snapshot();
    }

    private static int count(String rows) { return rows.isEmpty() ? 0 : rows.split(";").length; }

    private static void assertNoModuleBottle(ABSVisualisationFlowModel flow) {
        for (int index = 0; index < ABSVisualisationFlowModel.MODULE_COUNT; index++) {
            check(flow.getModuleSnapshot(index).getCurrentBottleKey().isEmpty(), "no bottle at completed upstream stage " + index);
            check(!flow.isModuleMoving(index), "no completed bottle animation " + index);
        }
        check(flow.invariantsHold(), "completion invariant");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
