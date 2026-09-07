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
        caseJSixPositionRotaryIdentity();
        caseKDelayedCompletionHoldsAtExit();
        caseLLongIdleAndElapsedTimerDoNotDrift();
        System.out.println(
            "ABSVisualisationFlowSelfTest PASS (" + assertions +
            " assertions; cases A-L)"
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
        ABSVisualisationFlowModel.ModuleSnapshot rotary =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.ROTARY);
        ABSVisualisationFlowModel.ModuleSnapshot fillerA =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.FILLER_A);
        ABSVisualisationFlowModel.ModuleSnapshot fillerB =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.FILLER_B);
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
        assertNear("E Rotary READY has no angle drift", 0.0,
            rotary.getRotaryAngle(), 0.0);
        assertNear("E Filler A READY has no liquid rise", 0.0,
            fillerA.getLiquidALevel(), 0.0);
        assertNear("E Filler B READY has no liquid rise", 0.0,
            fillerB.getLiquidBLevel(), 0.0);
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

    private static void caseJSixPositionRotaryIdentity() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        completeStage(model, ABSVisualisationFlowModel.LOADER);
        completeStage(model, ABSVisualisationFlowModel.CONVEYOR);
        completeStage(model, ABSVisualisationFlowModel.ROTARY);

        ABSVisualisationFlowModel.ModuleSnapshot rotary =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.ROTARY);
        assertEquals("J current M3 rotary has six positions", 6,
            rotary.getRotaryStationCount());
        assertEquals("J B1 identity is seated at P1", 1,
            rotary.getRotaryStationBottleId(0));
        assertEquals("J only one rotary position is occupied", 1,
            rotary.getRotaryOccupiedCount());
        for (int position = 1;
            position < rotary.getRotaryStationCount();
            position++) {
            assertEquals("J no duplicate B1 at rotary position " + position,
                0, rotary.getRotaryStationBottleId(position));
        }
        assertTrue("J rotary identity invariants", model.invariantsHold());
    }

    private static void caseKDelayedCompletionHoldsAtExit() {
        ABSVisualisationFlowModel model = new ABSVisualisationFlowModel();
        model.acceptRequired(1);
        routeBottleToUnloader(model);
        completeStage(model, ABSVisualisationFlowModel.UNLOADER);

        ABSVisualisationFlowModel.ModuleSnapshot waiting =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.UNLOADER);
        assertNear("K bottle reaches symbolic exit", 100.0,
            waiting.getProgress(), 0.001);
        assertEquals("K exit waits for real completed count",
            "WAITING FOR COMPLETION CONFIRMATION", waiting.getPhase());
        assertEquals("K unconfirmed exit lifecycle holds",
            ABSVisualisationFlowModel.ModuleLifecycle.HOLDING,
            waiting.getLifecycle());
        assertEquals("K visual completion does not run ahead", 0,
            model.getSnapshot().getVisualCompleted());
        assertEquals("K B1 remains at Unloader", 1,
            waiting.getCurrentBottleId());

        tick(model, 600);
        ABSVisualisationFlowModel.ModuleSnapshot stillWaiting =
            model.getSnapshot().getModule(ABSVisualisationFlowModel.UNLOADER);
        assertNear("K delayed evidence has no exit drift", 100.0,
            stillWaiting.getProgress(), 0.0);
        assertEquals("K delayed evidence keeps B1", 1,
            stillWaiting.getCurrentBottleId());
        assertEquals("K still no fake completion", 0,
            model.getSnapshot().getVisualCompleted());

        model.acceptCompleted(1);
        tick(model, 1);
        assertEquals("K real count releases visual completion", 1,
            model.getSnapshot().getVisualCompleted());
        assertEquals("K completed B1 leaves Unloader", 0,
            model.getSnapshot().getModule(
                ABSVisualisationFlowModel.UNLOADER).getCurrentBottleId());
    }

    private static void caseLLongIdleAndElapsedTimerDoNotDrift() {
        ABSVisualisationFlowModel idle = new ABSVisualisationFlowModel();
        idle.acceptRequired(3);
        for (int module = 0;
            module < ABSVisualisationFlowModel.MODULE_COUNT;
            module++) {
            idle.acceptStatus(module,
                ABSVisualisationFlowModel.READY_STATUS);
        }
        long now = 1000000000L;
        idle.tickElapsed(now);
        for (int frame = 0; frame < 5000; frame++) {
            now += 30000000L;
            idle.tickElapsed(now);
        }
        ABSVisualisationFlowModel.FlowSnapshot idleSnapshot =
            idle.getSnapshot();
        assertEquals("L long idle keeps all three bottles queued", 3,
            idleSnapshot.getQueuedCount());
        assertEquals("L long idle has no visual completion", 0,
            idleSnapshot.getVisualCompleted());
        for (int module = 0;
            module < ABSVisualisationFlowModel.MODULE_COUNT;
            module++) {
            assertNear("L long idle module has no drift " + module, 0.0,
                idleSnapshot.getModule(module).getProgress(), 0.0);
        }
        assertNear("L long idle Rotary has no drift", 0.0,
            idleSnapshot.getModule(
                ABSVisualisationFlowModel.ROTARY).getRotaryAngle(), 0.0);

        ABSVisualisationFlowModel stalledEdt =
            new ABSVisualisationFlowModel();
        stalledEdt.acceptRequired(1);
        stalledEdt.acceptStatus(ABSVisualisationFlowModel.LOADER,
            ABSVisualisationFlowModel.BUSY_STATUS);
        stalledEdt.tickElapsed(1000000000L);
        double beforeStall = moduleProgress(stalledEdt,
            ABSVisualisationFlowModel.LOADER);
        stalledEdt.tickElapsed(11000000000L);
        double afterStall = moduleProgress(stalledEdt,
            ABSVisualisationFlowModel.LOADER);
        assertTrue("L elapsed-time catch-up still advances active work",
            afterStall > beforeStall);
        assertAtMost("L stalled EDT is bounded to two frames",
            afterStall - beforeStall, 2.401);
        assertAtMost("L stalled EDT cannot cross BUSY hold",
            afterStall, ABSVisualisationFlowModel.BUSY_HOLD_PERCENT);
        assertTrue("L elapsed timer invariants", stalledEdt.invariantsHold());
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
