/** Test-only mutable state for the unified MockControllerCD. */
public final class MockStateV1 {
    private MockStateV1() {
    }

    public static final int NO_STAGE_EVENT = 0;
    public static final int BOTTLE_COLLECTED = 3;
    private static final int STAGE_COUNT = 8;
    private static final int QUEUED = -1;
    private static final int COMPLETE = 8;
    private static final int READY = 1;
    private static final int BUSY = 2;
    private static final int DONE = 3;
    private static final long STAGE_DURATION_MILLIS = Math.max(
        1L,
        Long.getLong("abs.mock.stageMillis", Long.valueOf(450L)).longValue()
    );
    private static final long BOTTLE_ADMISSION_MILLIS = Math.max(
        1L,
        Long.getLong(
            "abs.mock.admissionMillis",
            Long.valueOf(550L)
        ).longValue()
    );
    private static final long BOTTLE_DONE_HOLD_MILLIS = Math.max(
        1L,
        Long.getLong(
            "abs.mock.bottleDoneSignalHoldMillis",
            Long.valueOf(500L)
        ).longValue()
    );
    private static final String[] STAGE_NAMES = {
        "LOADER", "CONVEYOR", "ROTARY", "FILLER_A",
        "FILLER_B", "LID", "CAPPER", "UNLOADER"
    };

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
    public static long lastStartMillis = 0;
    public static long batchStartMillis = 0;
    public static boolean batchActive = false;

    private static int[] bottleStages = new int[0];
    private static long[] bottleStageReadyMillis = new long[0];
    private static final boolean[] firstBusyLogged =
        new boolean[STAGE_COUNT];
    private static int admittedBottles = 0;
    private static int pendingBottleDoneEvents = 0;
    private static long nextBottleAdmissionMillis = 0;
    private static boolean bottleDoneSignalActive = false;
    private static long bottleDoneSignalUntilMillis = 0L;
    private static boolean bottleDoneAbsentGapPending = false;

    /** Returns false for a near-immediate transport duplicate. */
    public static synchronized boolean startOrder(int quantity) {
        long now = System.currentTimeMillis();
        if (quantity <= 0 || batchActive) {
            return false;
        }
        lastStartMillis = now;
        batchStartMillis = now;
        requiredBottles = quantity;
        emittedBottleDone = 0;
        admittedBottles = 0;
        pendingBottleDoneEvents = 0;
        bottleDoneSignalActive = false;
        bottleDoneSignalUntilMillis = 0L;
        bottleDoneAbsentGapPending = false;
        nextBottleAdmissionMillis = now;
        bottleStages = new int[quantity];
        bottleStageReadyMillis = new long[quantity];
        for (int bottle = 0; bottle < quantity; bottle++) {
            bottleStages[bottle] = QUEUED;
            bottleStageReadyMillis[bottle] = 0L;
        }
        for (int stage = 0; stage < STAGE_COUNT; stage++) {
            firstBusyLogged[stage] = false;
        }
        batchActive = true;
        setAllStatuses(READY);
        System.out.println(
            "[MOCK-LIFECYCLE] start required=" + requiredBottles +
            " emitted=0 admitted=0"
        );
        return true;
    }

    /**
     * Advances the test-only final path: Capper, Conveyor output, Unloader.
     * BOTTLE_COLLECTED is returned only after the simulated Unloader stage.
     */
    public static synchronized int advanceBottleStage() {
        if ((!batchActive && pendingBottleDoneEvents == 0) ||
            requiredBottles <= 0) {
            return NO_STAGE_EVENT;
        }

        long now = System.currentTimeMillis();
        boolean keepBottleDonePresent = false;
        if (bottleDoneSignalActive) {
            if (now < bottleDoneSignalUntilMillis) {
                keepBottleDonePresent = true;
            }
            else {
                bottleDoneSignalActive = false;
                bottleDoneSignalUntilMillis = 0L;
                bottleDoneAbsentGapPending = true;
            }
        }

        // Move downstream first so one bottle leaving a stage can make room
        // before a following bottle is considered in the same bounded tick.
        for (int bottle = bottleStages.length - 1; bottle >= 0; bottle--) {
            int stage = bottleStages[bottle];
            if (stage < 0 || stage >= COMPLETE ||
                now < bottleStageReadyMillis[bottle]) {
                continue;
            }
            if (stage == UNLOADER_STAGE()) {
                bottleStages[bottle] = COMPLETE;
                emittedBottleDone++;
                pendingBottleDoneEvents++;
                System.out.println(
                    "[MOCK-LIFECYCLE] t=" + elapsed(now) +
                    "ms bottle=" + (bottle + 1) +
                    " COMPLETE emitted=" + emittedBottleDone + "/" +
                    requiredBottles
                );
            }
            else {
                bottleStages[bottle] = stage + 1;
                bottleStageReadyMillis[bottle] =
                    now + STAGE_DURATION_MILLIS;
            }
        }

        if (batchActive && admittedBottles < requiredBottles &&
            now >= nextBottleAdmissionMillis) {
            int bottle = admittedBottles++;
            bottleStages[bottle] = 0;
            bottleStageReadyMillis[bottle] =
                now + STAGE_DURATION_MILLIS;
            nextBottleAdmissionMillis = now + BOTTLE_ADMISSION_MILLIS;
        }

        if (batchActive) {
            deriveStatuses(now);
        }

        if (batchActive && emittedBottleDone == requiredBottles) {
            batchActive = false;
            setAllStatuses(DONE);
            System.out.println(
                "[MOCK-LIFECYCLE] batch DONE required=" +
                requiredBottles + " emitted=" + emittedBottleDone
            );
        }
        if (keepBottleDonePresent) {
            return BOTTLE_COLLECTED;
        }
        if (bottleDoneAbsentGapPending) {
            bottleDoneAbsentGapPending = false;
            return NO_STAGE_EVENT;
        }
        if (pendingBottleDoneEvents > 0) {
            pendingBottleDoneEvents--;
            bottleDoneSignalActive = true;
            bottleDoneSignalUntilMillis =
                now + BOTTLE_DONE_HOLD_MILLIS;
            return BOTTLE_COLLECTED;
        }
        return NO_STAGE_EVENT;
    }

    private static void deriveStatuses(long now) {
        boolean[] occupied = new boolean[STAGE_COUNT];
        for (int bottle = 0; bottle < bottleStages.length; bottle++) {
            int stage = bottleStages[bottle];
            if (stage >= 0 && stage < STAGE_COUNT) {
                occupied[stage] = true;
            }
        }
        for (int stage = 0; stage < STAGE_COUNT; stage++) {
            setStatus(stage, occupied[stage] ? BUSY : READY, now);
        }
    }

    private static void setAllStatuses(int status) {
        long now = System.currentTimeMillis();
        for (int stage = 0; stage < STAGE_COUNT; stage++) {
            setStatus(stage, status, now);
        }
    }

    private static void setStatus(int stage, int status, long now) {
        int previous = statusAt(stage);
        if (previous == status) {
            return;
        }
        assignStatus(stage, status);
        boolean firstBusy = status == BUSY && !firstBusyLogged[stage];
        if (firstBusy) {
            firstBusyLogged[stage] = true;
        }
        if (firstBusy || status == DONE) {
            System.out.println(
                "[MOCK-LIFECYCLE] t=" + elapsed(now) + "ms " +
                STAGE_NAMES[stage] + " " + statusName(previous) + "->" +
                statusName(status)
            );
        }
    }

    private static int statusAt(int stage) {
        switch (stage) {
            case 0: return loaderStatus;
            case 1: return conveyorStatus;
            case 2: return rotaryStatus;
            case 3: return fillerAStatus;
            case 4: return fillerBStatus;
            case 5: return lidStatus;
            case 6: return capperStatus;
            default: return unloaderStatus;
        }
    }

    private static void assignStatus(int stage, int status) {
        switch (stage) {
            case 0: loaderStatus = status; break;
            case 1: conveyorStatus = status; break;
            case 2: rotaryStatus = status; break;
            case 3: fillerAStatus = status; break;
            case 4: fillerBStatus = status; break;
            case 5: lidStatus = status; break;
            case 6: capperStatus = status; break;
            default: unloaderStatus = status; break;
        }
    }

    private static int UNLOADER_STAGE() {
        return STAGE_COUNT - 1;
    }

    private static long elapsed(long now) {
        return Math.max(0L, now - batchStartMillis);
    }

    private static String statusName(int status) {
        switch (status) {
            case READY: return "READY";
            case BUSY: return "BUSY";
            case DONE: return "DONE";
            default: return "UNKNOWN";
        }
    }
}
