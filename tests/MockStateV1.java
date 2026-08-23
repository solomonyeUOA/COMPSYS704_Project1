/** Test-only mutable state for the unified MockControllerCD. */
public final class MockStateV1 {
    private MockStateV1() {
    }

    public static final int NO_STAGE_EVENT = 0;
    public static final int CAPPER_STAGE_DONE = 1;
    public static final int CONVEYOR_OUTPUT_STAGE_DONE = 2;
    public static final int BOTTLE_COLLECTED = 3;
    private static final int READY_STAGE = -1;

    public static int loaderStatus = 1;
    public static int conveyorStatus = 1;
    public static int rotaryStatus = 1;
    public static int fillerAStatus = 1;
    public static int fillerBStatus = 1;
    public static int lidStatus = 1;
    public static int capperStatus = 1;
    public static int unloaderStatus = 1;

    public static int liquidARatio = 0;
    public static int liquidBRatio = 0;
    public static int requiredBottles = 0;
    public static int emittedBottleDone = 0;
    public static int completionStage = 0;
    public static long nextStageMillis = 0;
    public static long lastStartMillis = 0;

    /** Returns false for a near-immediate transport duplicate. */
    public static boolean startOrder(int quantity) {
        long now = System.currentTimeMillis();
        if (quantity == requiredBottles && now - lastStartMillis < 500) {
            return false;
        }

        lastStartMillis = now;
        requiredBottles = quantity;
        emittedBottleDone = 0;
        completionStage = READY_STAGE;
        nextStageMillis = now + 2000;
        setAllStatuses(1);
        return true;
    }

    /**
     * Advances the test-only final path: Capper, Conveyor output, Unloader.
     * BOTTLE_COLLECTED is returned only after the simulated Unloader stage.
     */
    public static int advanceBottleStage() {
        if (requiredBottles <= 0 || emittedBottleDone >= requiredBottles) {
            return NO_STAGE_EVENT;
        }

        long now = System.currentTimeMillis();
        if (now < nextStageMillis) {
            return NO_STAGE_EVENT;
        }

        // Keep READY observable long enough for the Coordinator's one-second
        // status polling, then begin the independent machine simulation.
        if (completionStage == READY_STAGE) {
            completionStage = 0;
            nextStageMillis = now + 1000;
            setAllStatuses(2);
            return NO_STAGE_EVENT;
        }

        completionStage++;
        nextStageMillis = now + 500;
        if (completionStage == CAPPER_STAGE_DONE) {
            return CAPPER_STAGE_DONE;
        }
        if (completionStage == CONVEYOR_OUTPUT_STAGE_DONE) {
            return CONVEYOR_OUTPUT_STAGE_DONE;
        }

        completionStage = 0;
        emittedBottleDone++;
        if (emittedBottleDone == requiredBottles) {
            setAllStatuses(3);
        }
        return BOTTLE_COLLECTED;
    }

    private static void setAllStatuses(int status) {
        loaderStatus = status;
        conveyorStatus = status;
        rotaryStatus = status;
        fillerAStatus = status;
        fillerBStatus = status;
        lidStatus = status;
        capperStatus = status;
        unloaderStatus = status;
    }
}
