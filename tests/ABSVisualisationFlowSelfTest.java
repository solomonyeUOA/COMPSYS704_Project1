import java.util.List;

/** Deterministic Java 8 regression tests for the M1 visual flow model. */
public final class ABSVisualisationFlowSelfTest {
    private static final int BUSY_TICKS = 150;
    private static final int COMPLETE_TICKS = 100;
    private static int assertions;

    private ABSVisualisationFlowSelfTest() {
    }

    public static void main(String[] args) {
        caseAOneBottleFullFlow();
        caseBThreeBottleSequentialPipeline();
        caseCMissedDoneBusyToReady();
        caseDDownstreamEvidenceBeforeUpstreamDone();
        caseEReadyOnlyCannotMove();
        caseFModuleLocalFaultFreezeAndResume();
        caseGBoundedCatchUpWithoutTeleport();
        caseHDuplicateStatusesDoNotDuplicateCycles();
        caseINewBatchResetHasNoStateLeakage();
        System.out.println(
            "ABSVisualisationFlowSelfTest PASS (" + assertions +
            " assertions; cases A-I)"
        );
    }

    private static void caseAOneBottleFullFlow() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        processBottleThroughPlant(model, 1);
        assertEquals("A visual completion", 1,
            model.getSnapshot().getVisualCompleted());
        assertTrue("A invariants", model.invariantsHold());
        assertSharedSnapshot(model, "A shared overview/detail snapshot");
    }

    private static void caseBThreeBottleSequentialPipeline() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(3);
        for (int completed = 1; completed <= 3; completed++) {
            processBottleThroughPlant(model, completed);
            assertEquals("B completed count " + completed, completed,
                model.getSnapshot().getVisualCompleted());
            assertTrue("B invariants " + completed, model.invariantsHold());
        }
        assertEquals("B exactly three Loader cycles", 3L,
            model.getStartedCycles(ABSVisualisationFlowModel.LOADER));
        assertEquals("B exactly three Loader claims", 3L,
            model.getClaimedCycles(ABSVisualisationFlowModel.LOADER));
        assertUniqueBottleIdentities(model.getSnapshot().getBottles(), 3);

        // A fast real pipeline may finish three bottles while the one-second
        // Coordinator poll samples only one Loader cycle. The final count is
        // trustworthy evidence, but visual catch-up must still be gradual.
        ABSVisualisationFlowModel sparse = new ABSVisualisationFlowModel();
        sparse.acceptRequired(3);
        sparse.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(sparse, 12);
        sparse.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.READY_STATUS);
        sparse.acceptCompleted(3);
        tick(sparse, 1);
        assertEquals("B sparse polling does not teleport completion", 0,
            sparse.getSnapshot().getVisualCompleted());
        tick(sparse, 2600);
        assertEquals("B sparse polling converges from completed evidence", 3,
            sparse.getSnapshot().getVisualCompleted());
        assertEquals("B sparse polling reconstructs three Loader cycles", 3L,
            sparse.getClaimedCycles(ABSVisualisationFlowModel.LOADER));
        assertTrue("B sparse polling invariants", sparse.invariantsHold());
    }

    private static void caseCMissedDoneBusyToReady() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, BUSY_TICKS);
        assertAtMost("C BUSY cannot complete", moduleProgress(model,
            ABSVisualisationFlowModel.LOADER),
            ABSVisualisationFlowModel.BUSY_HOLD_PERCENT);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.READY_STATUS);
        tick(model, COMPLETE_TICKS);
        assertNear("C BUSY->READY supplies completion evidence", 100.0,
            moduleProgress(model, ABSVisualisationFlowModel.LOADER), 0.001);
    }

    private static void caseDDownstreamEvidenceBeforeUpstreamDone() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, BUSY_TICKS);
        model.acceptStatus(ABSVisualisationFlowModel.CONVEYOR,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, COMPLETE_TICKS + 20);
        ABSVisualisationFlowModel.ModuleSnapshot conveyor =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.CONVEYOR);
        assertEquals("D downstream evidence preserves bottle identity", 1,
            conveyor.getCurrentBottleId());
        assertTrue("D downstream stage moves", conveyor.getProgress() > 0.0);
        assertTrue("D invariants", model.invariantsHold());
    }

    private static void caseEReadyOnlyCannotMove() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(3);
        for (int module = 0;
            module < ABSVisualisationFlowModel.MODULE_COUNT;
            module++) {
            model.acceptStatus(module,
                ABSVisualisationFlowModel.READY_STATUS);
        }
        tick(model, 300);
        for (int module = 0;
            module < ABSVisualisationFlowModel.MODULE_COUNT;
            module++) {
            assertNear("E READY cannot move module " + module, 0.0,
                moduleProgress(model, module), 0.0);
        }
        assertEquals("E no Loader cycle", 0L,
            model.getStartedCycles(ABSVisualisationFlowModel.LOADER));
        assertEquals("E no visual completions", 0,
            model.getSnapshot().getVisualCompleted());
    }

    private static void caseFModuleLocalFaultFreezeAndResume() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        completeStage(model, ABSVisualisationFlowModel.LOADER);
        model.acceptStatus(ABSVisualisationFlowModel.CONVEYOR,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, 20);
        double beforeOtherFault = moduleProgress(model,
            ABSVisualisationFlowModel.CONVEYOR);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.FAULT_STATUS);
        tick(model, 20);
        double afterOtherFault = moduleProgress(model,
            ABSVisualisationFlowModel.CONVEYOR);
        assertTrue("F unrelated fault does not freeze Conveyor",
            afterOtherFault > beforeOtherFault);

        model.acceptStatus(ABSVisualisationFlowModel.CONVEYOR,
            ABSVisualisationFlowModel.FAULT_STATUS);
        double frozen = moduleProgress(model,
            ABSVisualisationFlowModel.CONVEYOR);
        tick(model, 80);
        assertNear("F corresponding module freezes", frozen,
            moduleProgress(model, ABSVisualisationFlowModel.CONVEYOR), 0.0);
        int bottleId = model.getSnapshot().getModule(
            ABSVisualisationFlowModel.CONVEYOR).getCurrentBottleId();

        model.acceptStatus(ABSVisualisationFlowModel.CONVEYOR,
            ABSVisualisationFlowModel.READY_STATUS);
        tick(model, 20);
        assertNear("F READY alone does not resume motion", frozen,
            moduleProgress(model, ABSVisualisationFlowModel.CONVEYOR), 0.0);
        model.acceptStatus(ABSVisualisationFlowModel.CONVEYOR,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, 20);
        assertTrue("F new BUSY evidence resumes motion",
            moduleProgress(model, ABSVisualisationFlowModel.CONVEYOR) > frozen);
        assertEquals("F bottle identity survives fault", bottleId,
            model.getSnapshot().getModule(
                ABSVisualisationFlowModel.CONVEYOR).getCurrentBottleId());
    }

    private static void caseGBoundedCatchUpWithoutTeleport() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        routeBottleToUnloader(model);
        model.acceptStatus(ABSVisualisationFlowModel.UNLOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, 12);
        double before = moduleProgress(model,
            ABSVisualisationFlowModel.UNLOADER);
        model.acceptCompleted(1);
        tick(model, 1);
        double afterOneTick = moduleProgress(model,
            ABSVisualisationFlowModel.UNLOADER);
        assertTrue("G completed fact does not teleport", afterOneTick < 100.0);
        assertAtMost("G one-tick movement is bounded",
            afterOneTick - before,
            1.20 * ABSVisualisationFlowModel.MAX_CATCH_UP_MULTIPLIER + 0.001);
        assertAtMost("G catch-up multiplier bound",
            model.getCatchUpMultiplier(),
            ABSVisualisationFlowModel.MAX_CATCH_UP_MULTIPLIER);
        tick(model, COMPLETE_TICKS + 80);
        assertEquals("G eventually reconciles", 1,
            model.getSnapshot().getVisualCompleted());
        assertTrue("G visual count never exceeds real count",
            model.getSnapshot().getVisualCompleted() <=
                model.getSnapshot().getRealCompleted());
    }

    private static void caseHDuplicateStatusesDoNotDuplicateCycles() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(3);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, 10);
        assertEquals("H duplicate BUSY is one real cycle", 1L,
            model.getStartedCycles(ABSVisualisationFlowModel.LOADER));
        assertEquals("H duplicate BUSY is one claimed cycle", 1L,
            model.getClaimedCycles(ABSVisualisationFlowModel.LOADER));
        int moving = 0;
        for (ABSVisualisationFlowModel.BottleSnapshot bottle :
            model.getSnapshot().getBottles()) {
            if (bottle.getStage() >= 0) {
                moving++;
            }
        }
        assertEquals("H one Loader cycle admits exactly one bottle", 1, moving);
        assertTrue("H invariants", model.invariantsHold());
    }

    private static void caseINewBatchResetHasNoStateLeakage() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        model.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        tick(model, 20);
        int oldGeneration = model.getSnapshot().getBatchGeneration();
        model.acceptRequired(2);
        model.acceptCompleted(0);
        assertEquals("I explicit transition mode", "BATCH TRANSITION",
            model.getSnapshot().getMode());
        tick(model, 8);
        ABSVisualisationFlowModel.FlowSnapshot reset = model.getSnapshot();
        assertTrue("I generation changes",
            reset.getBatchGeneration() > oldGeneration);
        assertEquals("I new queue size", 2, reset.getBottles().size());
        assertEquals("I completed state cleared", 0,
            reset.getVisualCompleted());
        for (ABSVisualisationFlowModel.BottleSnapshot bottle :
            reset.getBottles()) {
            assertEquals("I no old stage state", -1, bottle.getStage());
            assertNear("I no old progress", 0.0, bottle.getProgress(), 0.0);
            assertEquals("I no old lifecycle",
                ABSVisualisationFlowModel.BottleLifecycle.QUEUED,
                bottle.getLifecycle());
            assertEquals("I current generation only",
                reset.getBatchGeneration(), bottle.getBatchGeneration());
        }
        assertTrue("I invariants", model.invariantsHold());
    }

    private static void processBottleThroughPlant(
        ABSVisualisationFlowModel model,
        int realCompleted
    ) {
        routeBottleToUnloader(model);
        completeStage(model, ABSVisualisationFlowModel.UNLOADER);
        model.acceptCompleted(realCompleted);
        tick(model, COMPLETE_TICKS + 20);
    }

    private static void routeBottleToUnloader(
        ABSVisualisationFlowModel model
    ) {
        completeStage(model, ABSVisualisationFlowModel.LOADER);
        completeStage(model, ABSVisualisationFlowModel.CONVEYOR);
        completeStage(model, ABSVisualisationFlowModel.ROTARY);
        completeStage(model, ABSVisualisationFlowModel.FILLER_A);
        completeStage(model, ABSVisualisationFlowModel.FILLER_B);
        completeStage(model, ABSVisualisationFlowModel.LID);
        completeStage(model, ABSVisualisationFlowModel.CAPPER);
    }

    private static void completeStage(
        ABSVisualisationFlowModel model,
        int module
    ) {
        model.acceptStatus(module, ABSVisualisationFlowModel.BUSY_STATUS);
        double previousProgress = moduleProgress(model, module);
        int previousStage = bottleStageAtModule(model, module);
        for (int tick = 0; tick < BUSY_TICKS; tick++) {
            model.tick();
            double progress = moduleProgress(model, module);
            int stage = bottleStageAtModule(model, module);
            if (stage == previousStage && stage >= 0) {
                assertTrue("no backward motion at module " + module,
                    progress + 0.000001 >= previousProgress);
            }
            previousProgress = progress;
            previousStage = stage;
        }
        assertAtMost("BUSY holds before completion at module " + module,
            moduleProgress(model, module),
            ABSVisualisationFlowModel.BUSY_HOLD_PERCENT + 0.001);
        model.acceptStatus(module, ABSVisualisationFlowModel.READY_STATUS);
        tick(model, COMPLETE_TICKS);
    }

    private static int bottleStageAtModule(
        ABSVisualisationFlowModel model,
        int module
    ) {
        int id = model.getSnapshot().getModule(module).getCurrentBottleId();
        if (id <= 0) {
            return -1;
        }
        for (ABSVisualisationFlowModel.BottleSnapshot bottle :
            model.getSnapshot().getBottles()) {
            if (bottle.getDisplayId() == id) {
                return bottle.getStage();
            }
        }
        return -1;
    }

    private static double moduleProgress(
        ABSVisualisationFlowModel model,
        int module
    ) {
        return model.getSnapshot().getModule(module).getProgress();
    }

    private static void tick(ABSVisualisationFlowModel model, int count) {
        for (int index = 0; index < count; index++) {
            model.tick();
            assertTrue("global invariants at tick " + index,
                model.invariantsHold());
            assertTrue("visual complete not ahead of real",
                model.getSnapshot().getVisualCompleted() <=
                    model.getSnapshot().getRealCompleted());
        }
    }

    private static void assertSharedSnapshot(
        ABSVisualisationFlowModel model,
        String message
    ) {
        ABSVisualisationFlowModel.FlowSnapshot snapshot = model.getSnapshot();
        assertTrue(message,
            snapshot.getModule(ABSVisualisationFlowModel.LOADER) ==
                model.getModuleSnapshot(ABSVisualisationFlowModel.LOADER));
        for (int module = 0;
            module < ABSVisualisationFlowModel.MODULE_COUNT;
            module++) {
            assertEquals(message + " version " + module,
                snapshot.getVersion(), snapshot.getModule(module).getVersion());
        }
    }

    private static void assertUniqueBottleIdentities(
        List<ABSVisualisationFlowModel.BottleSnapshot> bottles,
        int expected
    ) {
        boolean[] seen = new boolean[expected + 1];
        assertEquals("identity count", expected, bottles.size());
        for (ABSVisualisationFlowModel.BottleSnapshot bottle : bottles) {
            int id = bottle.getDisplayId();
            assertTrue("identity range", id > 0 && id <= expected);
            assertTrue("identity unique " + id, !seen[id]);
            seen[id] = true;
        }
    }

    private static void assertAtMost(
        String message,
        double actual,
        double maximum
    ) {
        assertions++;
        if (actual > maximum + 0.000001) {
            throw new AssertionError(
                message + ": expected <= " + maximum + ", actual=" + actual
            );
        }
    }

    private static void assertNear(
        String message,
        double expected,
        double actual,
        double tolerance
    ) {
        assertions++;
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertEquals(
        String message,
        long expected,
        long actual
    ) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertEquals(
        String message,
        Object expected,
        Object actual
    ) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual
            );
        }
    }

    private static void assertTrue(String message, boolean condition) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
