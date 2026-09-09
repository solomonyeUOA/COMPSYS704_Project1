import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic, read-only visual reconciliation model for ABSVisualisation.
 *
 * The model consumes only the frozen M1 visualisation boundary. It never sends
 * commands and deliberately does not claim to know physical bottle positions,
 * bottle identifiers, recipe ratios, or timing. Status edges are evidence for
 * a symbolic stage; the published geometry is an immutable UI snapshot.
 */
final class ABSVisualisationFlowModel {
    static final int LOADER = 0;
    static final int CONVEYOR = 1;
    static final int ROTARY = 2;
    static final int FILLER_A = 3;
    static final int FILLER_B = 4;
    static final int LID = 5;
    static final int CAPPER = 6;
    static final int UNLOADER = 7;
    static final int MODULE_COUNT = 8;

    static final int IDLE_STATUS = 0;
    static final int READY_STATUS = 1;
    static final int BUSY_STATUS = 2;
    static final int DONE_STATUS = 3;
    static final int FAULT_STATUS = 4;

    static final double BUSY_HOLD_PERCENT = 92.0;
    static final double MAX_CATCH_UP_MULTIPLIER = 2.2;
    private static final int ROTARY_STATION_COUNT = 6;
    private static final double ROTARY_STEP_DEGREES = 60.0;
    private static final int BATCH_TRANSITION_TICKS = 8;
    private static final double COMPLETE_EPSILON = 0.001;
    private static final boolean TRACE_ENABLED =
        Boolean.getBoolean("abs.visualisation.trace");

    enum BottleLifecycle {
        QUEUED,
        ENTERING_STAGE,
        PROCESSING,
        WAITING_FOR_HANDOFF,
        EXITING_STAGE,
        WAITING_FOR_REAL_CONFIRMATION,
        COMPLETED
    }

    enum ModuleLifecycle {
        WAITING,
        ENTERING,
        ACTIVE,
        EXITING,
        HOLDING,
        COMPLETE,
        FAULTED
    }

    private static final class BottleState {
        private final int displayId;
        private final int batchGeneration;
        private int stage;
        private double progress;
        private BottleLifecycle lifecycle;

        BottleState(int id, int generation) {
            displayId = id;
            batchGeneration = generation;
            stage = -1;
            progress = 0.0;
            lifecycle = BottleLifecycle.QUEUED;
        }
    }

    private static final class WorkState {
        private final BottleState bottle;
        private long cycleNumber;
        private double progress;
        private boolean completionConfirmed;

        WorkState(BottleState value, long cycle) {
            bottle = value;
            cycleNumber = cycle;
            progress = 0.0;
            completionConfirmed = false;
        }
    }

    private static final class RotaryCycleState {
        private final long cycleNumber;
        private final BottleState entryBottle;
        private final boolean entryCycle;
        private double progress;
        private boolean completionConfirmed;

        RotaryCycleState(
            long cycle,
            BottleState bottle,
            boolean isEntry
        ) {
            cycleNumber = cycle;
            entryBottle = bottle;
            entryCycle = isEntry;
            progress = 0.0;
            completionConfirmed = false;
        }
    }

    static final class BottleSnapshot {
        private final int displayId;
        private final int batchGeneration;
        private final int stage;
        private final double progress;
        private final BottleLifecycle lifecycle;

        BottleSnapshot(BottleState state) {
            displayId = state.displayId;
            batchGeneration = state.batchGeneration;
            stage = state.stage;
            progress = state.progress;
            lifecycle = state.lifecycle;
        }

        int getDisplayId() {
            return displayId;
        }

        int getBatchGeneration() {
            return batchGeneration;
        }

        int getStage() {
            return stage;
        }

        double getProgress() {
            return progress;
        }

        BottleLifecycle getLifecycle() {
            return lifecycle;
        }
    }

    /** Immutable rendering data for one symbolic module. */
    static final class ModuleSnapshot {
        private final long version;
        private final int moduleIndex;
        private final double progress;
        private final double conveyorBottlePosition;
        private final double rollerAngle;
        private final double rotaryAngle;
        private final double rotaryEntryProgress;
        private final double rotaryExitProgress;
        private final int[] rotaryStationBottleIds;
        private final int rotaryBottlesEntered;
        private final int rotaryBottlesExited;
        private final double liquidALevel;
        private final double liquidBLevel;
        private final double tighteningAngle;
        private final String rotaryPhase;
        private final String phase;
        private final ModuleLifecycle lifecycle;
        private final int currentBottleId;
        private final boolean running;

        ModuleSnapshot(
            long snapshotVersion,
            int index,
            double moduleProgress,
            double conveyorPosition,
            double roller,
            double rotary,
            double entryProgress,
            double exitProgress,
            int[] stationBottleIds,
            int entered,
            int exited,
            double liquidA,
            double liquidB,
            double capAngle,
            String currentRotaryPhase,
            String currentPhase,
            ModuleLifecycle currentLifecycle,
            int bottleId,
            boolean isRunning
        ) {
            version = snapshotVersion;
            moduleIndex = index;
            progress = moduleProgress;
            conveyorBottlePosition = conveyorPosition;
            rollerAngle = roller;
            rotaryAngle = rotary;
            rotaryEntryProgress = entryProgress;
            rotaryExitProgress = exitProgress;
            rotaryStationBottleIds = stationBottleIds.clone();
            rotaryBottlesEntered = entered;
            rotaryBottlesExited = exited;
            liquidALevel = liquidA;
            liquidBLevel = liquidB;
            tighteningAngle = capAngle;
            rotaryPhase = currentRotaryPhase;
            phase = currentPhase;
            lifecycle = currentLifecycle;
            currentBottleId = bottleId;
            running = isRunning;
        }

        long getVersion() {
            return version;
        }

        int getModuleIndex() {
            return moduleIndex;
        }

        double getProgress() {
            return progress;
        }

        double getConveyorBottlePosition() {
            return conveyorBottlePosition;
        }

        double getRollerAngle() {
            return rollerAngle;
        }

        double getRotaryAngle() {
            return rotaryAngle;
        }

        double getRotaryEntryProgress() {
            return rotaryEntryProgress;
        }

        double getRotaryExitProgress() {
            return rotaryExitProgress;
        }

        int getRotaryStationCount() {
            return rotaryStationBottleIds.length;
        }

        boolean isRotaryStationOccupied(int station) {
            return getRotaryStationBottleId(station) > 0;
        }

        int getRotaryStationBottleId(int station) {
            return station >= 0 &&
                station < rotaryStationBottleIds.length ?
                    rotaryStationBottleIds[station] : 0;
        }

        int getRotaryOccupiedCount() {
            int occupied = 0;
            for (int index = 0;
                index < rotaryStationBottleIds.length;
                index++) {
                if (rotaryStationBottleIds[index] > 0) {
                    occupied++;
                }
            }
            return occupied;
        }

        int getRotaryBottlesEntered() {
            return rotaryBottlesEntered;
        }

        int getRotaryBottlesExited() {
            return rotaryBottlesExited;
        }

        double getLiquidALevel() {
            return liquidALevel;
        }

        double getLiquidBLevel() {
            return liquidBLevel;
        }

        double getTotalFillLevel() {
            return Math.min(100.0, liquidALevel + liquidBLevel);
        }

        double getTighteningAngle() {
            return tighteningAngle;
        }

        String getRotaryPhase() {
            return rotaryPhase;
        }

        String getPhase() {
            return phase;
        }

        ModuleLifecycle getLifecycle() {
            return lifecycle;
        }

        int getCurrentBottleId() {
            return currentBottleId;
        }

        boolean isRunning() {
            return running;
        }
    }

    /** One immutable graph shared by overview and all detail views. */
    static final class FlowSnapshot {
        private final long version;
        private final int batchGeneration;
        private final int required;
        private final int realCompleted;
        private final int visualCompleted;
        private final String mode;
        private final ModuleSnapshot[] modules;
        private final List<BottleSnapshot> bottles;

        FlowSnapshot(
            long snapshotVersion,
            int generation,
            int requiredCount,
            int actualCompleted,
            int displayedCompleted,
            String currentMode,
            ModuleSnapshot[] moduleSnapshots,
            List<BottleSnapshot> bottleSnapshots
        ) {
            version = snapshotVersion;
            batchGeneration = generation;
            required = requiredCount;
            realCompleted = actualCompleted;
            visualCompleted = displayedCompleted;
            mode = currentMode;
            modules = moduleSnapshots.clone();
            bottles = Collections.unmodifiableList(bottleSnapshots);
        }

        long getVersion() {
            return version;
        }

        int getBatchGeneration() {
            return batchGeneration;
        }

        int getRequired() {
            return required;
        }

        int getRealCompleted() {
            return realCompleted;
        }

        int getVisualCompleted() {
            return visualCompleted;
        }

        String getMode() {
            return mode;
        }

        ModuleSnapshot getModule(int index) {
            if (index < 0 || index >= modules.length) {
                throw new IllegalArgumentException("module index " + index);
            }
            return modules[index];
        }

        List<BottleSnapshot> getBottles() {
            return bottles;
        }

        int getQueuedCount() {
            int queued = 0;
            for (BottleSnapshot bottle : bottles) {
                if (bottle.getLifecycle() == BottleLifecycle.QUEUED) {
                    queued++;
                }
            }
            return queued;
        }
    }

    private final int[] statuses = new int[MODULE_COUNT];
    private final boolean[] hasStatus = new boolean[MODULE_COUNT];
    private final boolean[] cycleOpen = new boolean[MODULE_COUNT];
    private final long[] startedCycles = new long[MODULE_COUNT];
    private final long[] completedCycles = new long[MODULE_COUNT];
    private final long[] claimedCycles = new long[MODULE_COUNT];
    private final WorkState[] work = new WorkState[MODULE_COUNT];
    private final BottleState[] rotaryStations =
        new BottleState[ROTARY_STATION_COUNT];
    private final List<BottleState> bottles = new ArrayList<BottleState>();

    private RotaryCycleState rotaryCycle;
    private int required;
    private int realCompleted;
    private int visualCompleted;
    private int nextBottleToAdmit;
    private int batchGeneration;
    private int pendingRequired = -1;
    private int batchTransitionTicks;
    private int rotaryBottlesEntered;
    private int rotaryBottlesExited;
    private double rollerAngle;
    private double rotaryBaseAngle;
    private long version;
    private long lastRealtimeTickNanos;
    private FlowSnapshot published;

    ABSVisualisationFlowModel() {
        publish();
    }

    synchronized void acceptRequired(int value) {
        int safeValue = Math.max(0, value);
        if (bottles.isEmpty() && required == 0 && pendingRequired < 0) {
            resetBatchNow(safeValue);
        }
        else if (safeValue != required || pendingRequired >= 0) {
            pendingRequired = safeValue;
            batchTransitionTicks = BATCH_TRANSITION_TICKS;
        }
        publish();
    }

    synchronized void acceptCompleted(int value) {
        int safeValue = Math.max(0, value);
        if (safeValue < realCompleted) {
            pendingRequired = pendingRequired >= 0 ? pendingRequired : required;
            batchTransitionTicks = BATCH_TRANSITION_TICKS;
        }
        realCompleted = required > 0 ? Math.min(safeValue, required) : safeValue;
        backfillMissedCyclesFromCompletedCount();
        confirmUnloaderFromRealCount();
        publish();
    }

    /** Clears all symbolic/animation state after a confirmed reset request. */
    synchronized void resetSystem() {
        resetBatchNow(0);
        lastRealtimeTickNanos = 0L;
        for (int index = 0; index < MODULE_COUNT; index++) {
            statuses[index] = IDLE_STATUS;
            hasStatus[index] = true;
        }
        publish();
    }

    synchronized void acceptStatus(int index, int status) {
        checkModuleIndex(index);
        if (hasStatus[index] && statuses[index] == status) {
            return;
        }

        int previous = hasStatus[index] ? statuses[index] : Integer.MIN_VALUE;
        hasStatus[index] = true;
        statuses[index] = status;

        if (status == BUSY_STATUS) {
            startedCycles[index]++;
            cycleOpen[index] = true;
            if (previous == FAULT_STATUS && index != ROTARY &&
                work[index] != null) {
                claimedCycles[index] = startedCycles[index];
                work[index].cycleNumber = startedCycles[index];
                work[index].completionConfirmed = false;
            }
            if (index > LOADER) {
                confirmPreviousStage(index);
            }
        }
        else if (status == DONE_STATUS) {
            if (!cycleOpen[index]) {
                boolean hasUnfinishedWork = index == ROTARY ?
                    rotaryCycle != null : work[index] != null;
                if (!hasUnfinishedWork ||
                    claimedCycles[index] >= startedCycles[index]) {
                    startedCycles[index]++;
                }
            }
            completedCycles[index] = Math.max(
                completedCycles[index],
                startedCycles[index]
            );
            cycleOpen[index] = false;
            confirmCurrentCycle(index);
        }
        else if ((status == READY_STATUS || status == IDLE_STATUS) &&
            previous == BUSY_STATUS) {
            completedCycles[index] = Math.max(
                completedCycles[index],
                startedCycles[index]
            );
            cycleOpen[index] = false;
            confirmCurrentCycle(index);
        }

        publish();
    }

    synchronized void tick() {
        tickInternal(1.0);
    }

    /** EDT entry point: coalesced timer delays are bounded to two frames. */
    synchronized void tickElapsed(long nowNanos) {
        double frameScale = 1.0;
        if (lastRealtimeTickNanos > 0L && nowNanos > lastRealtimeTickNanos) {
            double elapsedMillis =
                (nowNanos - lastRealtimeTickNanos) / 1000000.0;
            frameScale = Math.max(0.33, Math.min(2.0,
                elapsedMillis / 30.0));
        }
        lastRealtimeTickNanos = nowNanos;
        tickInternal(frameScale);
    }

    private void tickInternal(double frameScale) {
        if (batchTransitionTicks > 0) {
            batchTransitionTicks--;
            if (batchTransitionTicks == 0 && pendingRequired >= 0) {
                resetBatchNow(pendingRequired);
            }
            publish();
            return;
        }

        confirmUnloaderFromRealCount();
        backfillMissedCyclesFromCompletedCount();
        claimPendingStages();
        advanceLinearWork(frameScale);
        advanceRotaryCycle(frameScale);
        claimPendingStages();
        finishVisuallyConfirmedBottle();
        publish();
    }

    synchronized FlowSnapshot getSnapshot() {
        return published;
    }

    synchronized ModuleSnapshot getModuleSnapshot(int index) {
        checkModuleIndex(index);
        return published.getModule(index);
    }

    synchronized int getVisualCompleted() {
        return visualCompleted;
    }

    synchronized String getModeName() {
        return published.getMode();
    }

    synchronized boolean isModuleMoving(int index) {
        checkModuleIndex(index);
        return published.getModule(index).isRunning();
    }

    synchronized long getStartedCycles(int index) {
        checkModuleIndex(index);
        return startedCycles[index];
    }

    synchronized long getClaimedCycles(int index) {
        checkModuleIndex(index);
        return claimedCycles[index];
    }

    synchronized double getCatchUpMultiplier() {
        return calculateCatchUpMultiplier();
    }

    synchronized boolean invariantsHold() {
        boolean[] seen = new boolean[Math.max(1, bottles.size() + 1)];
        for (int index = 0; index < work.length; index++) {
            if (index == ROTARY || work[index] == null) {
                continue;
            }
            int id = work[index].bottle.displayId;
            if (id <= 0 || id >= seen.length || seen[id]) {
                return false;
            }
            seen[id] = true;
        }
        if (rotaryCycle != null && rotaryCycle.entryBottle != null) {
            int id = rotaryCycle.entryBottle.displayId;
            if (id <= 0 || id >= seen.length || seen[id]) {
                return false;
            }
            seen[id] = true;
        }
        for (int station = 0; station < rotaryStations.length; station++) {
            if (rotaryStations[station] != null) {
                int id = rotaryStations[station].displayId;
                if (id <= 0 || id >= seen.length || seen[id]) {
                    return false;
                }
                seen[id] = true;
            }
        }
        return visualCompleted <= realCompleted &&
            visualCompleted <= required;
    }

    private void claimPendingStages() {
        claimLinearStage(UNLOADER, CAPPER);
        claimLinearStage(CAPPER, LID);
        claimLinearStage(LID, FILLER_B);
        claimLinearStage(FILLER_B, FILLER_A);
        claimFillerAFromRotary();
        claimRotaryCycle();
        claimLinearStage(CONVEYOR, LOADER);
        claimLoaderCycle();
    }

    private void claimLoaderCycle() {
        if (work[LOADER] != null ||
            claimedCycles[LOADER] >= startedCycles[LOADER] ||
            nextBottleToAdmit >= bottles.size()) {
            return;
        }
        BottleState bottle = bottles.get(nextBottleToAdmit++);
        long cycle = claimedCycles[LOADER] + 1L;
        claimedCycles[LOADER] = cycle;
        work[LOADER] = new WorkState(bottle, cycle);
        work[LOADER].completionConfirmed =
            completedCycles[LOADER] >= cycle;
        bottle.stage = LOADER;
        bottle.progress = 0.0;
        bottle.lifecycle = BottleLifecycle.ENTERING_STAGE;
        traceModel(bottle, "LOADER");
    }

    private void claimLinearStage(int target, int source) {
        if (work[target] != null ||
            claimedCycles[target] >= startedCycles[target]) {
            return;
        }
        WorkState previous = work[source];
        if (previous == null) {
            return;
        }
        previous.completionConfirmed = true;
        if (previous.progress < 100.0 - COMPLETE_EPSILON ||
            statuses[source] == FAULT_STATUS) {
            return;
        }

        BottleState bottle = previous.bottle;
        work[source] = null;
        long cycle = claimedCycles[target] + 1L;
        claimedCycles[target] = cycle;
        work[target] = new WorkState(bottle, cycle);
        work[target].completionConfirmed = completedCycles[target] >= cycle;
        bottle.stage = target;
        bottle.progress = 0.0;
        bottle.lifecycle = BottleLifecycle.ENTERING_STAGE;
        traceModel(bottle, moduleName(target));
    }

    private void claimFillerAFromRotary() {
        if (work[FILLER_A] != null ||
            claimedCycles[FILLER_A] >= startedCycles[FILLER_A]) {
            return;
        }
        int station = highestOccupiedRotaryStation();
        if (station < 0 || statuses[ROTARY] == FAULT_STATUS) {
            return;
        }

        BottleState bottle = rotaryStations[station];
        rotaryStations[station] = null;
        rotaryBottlesExited++;
        long cycle = claimedCycles[FILLER_A] + 1L;
        claimedCycles[FILLER_A] = cycle;
        work[FILLER_A] = new WorkState(bottle, cycle);
        work[FILLER_A].completionConfirmed =
            completedCycles[FILLER_A] >= cycle;
        bottle.stage = FILLER_A;
        bottle.progress = 0.0;
        bottle.lifecycle = BottleLifecycle.ENTERING_STAGE;
        traceModel(bottle, "FILLER_A");
    }

    private void claimRotaryCycle() {
        if (rotaryCycle != null ||
            claimedCycles[ROTARY] >= startedCycles[ROTARY]) {
            return;
        }

        WorkState conveyorWork = work[CONVEYOR];
        boolean mayEnter = conveyorWork != null &&
            conveyorWork.completionConfirmed &&
            conveyorWork.progress >= 100.0 - COMPLETE_EPSILON &&
            rotaryStations[0] == null &&
            statuses[CONVEYOR] != FAULT_STATUS;
        boolean completedEvidenceNeedsEntry = rotaryBottlesEntered <
            Math.min(realCompleted, bottles.size());
        boolean mayRotate = !mayEnter && !completedEvidenceNeedsEntry &&
            rotaryOccupiedCount() > 0 &&
            rotaryStations[ROTARY_STATION_COUNT - 1] == null;
        if (!mayEnter && !mayRotate) {
            return;
        }

        long cycle = claimedCycles[ROTARY] + 1L;
        claimedCycles[ROTARY] = cycle;
        BottleState entryBottle = mayEnter ? conveyorWork.bottle : null;
        if (mayEnter) {
            work[CONVEYOR] = null;
            entryBottle.stage = ROTARY;
            entryBottle.progress = 0.0;
            entryBottle.lifecycle = BottleLifecycle.ENTERING_STAGE;
            traceModel(entryBottle, "ROTARY_ENTRY");
        }
        rotaryCycle = new RotaryCycleState(
            cycle,
            entryBottle,
            mayEnter
        );
        rotaryCycle.completionConfirmed = completedCycles[ROTARY] >= cycle;
    }

    private void advanceLinearWork(double frameScale) {
        for (int index = 0; index < MODULE_COUNT; index++) {
            if (index == ROTARY || work[index] == null) {
                continue;
            }
            WorkState current = work[index];
            if (completedCycles[index] >= current.cycleNumber) {
                current.completionConfirmed = true;
            }
            if (index == UNLOADER && realCompleted > visualCompleted) {
                current.completionConfirmed = true;
            }
            if (statuses[index] == FAULT_STATUS) {
                current.bottle.lifecycle = BottleLifecycle.PROCESSING;
                continue;
            }

            double target = current.completionConfirmed ? 100.0 :
                (statuses[index] == BUSY_STATUS ? BUSY_HOLD_PERCENT :
                    current.progress);
            current.progress = advanceToward(
                current.progress,
                target,
                frameScale
            );
            current.bottle.progress = current.progress;
            if (current.completionConfirmed &&
                current.progress < 100.0 - COMPLETE_EPSILON) {
                current.bottle.lifecycle = BottleLifecycle.EXITING_STAGE;
            }
            else if (current.progress >= BUSY_HOLD_PERCENT -
                COMPLETE_EPSILON && !current.completionConfirmed) {
                current.bottle.lifecycle =
                    BottleLifecycle.WAITING_FOR_REAL_CONFIRMATION;
            }
            else if (current.progress >= 100.0 - COMPLETE_EPSILON) {
                current.bottle.lifecycle = BottleLifecycle.WAITING_FOR_HANDOFF;
            }
            else {
                current.bottle.lifecycle = BottleLifecycle.PROCESSING;
            }

            if (index == CONVEYOR && statuses[index] == BUSY_STATUS) {
                rollerAngle = normaliseAngle(rollerAngle +
                    5.0 * calculateCatchUpMultiplier() * frameScale);
            }
        }
    }

    private void advanceRotaryCycle(double frameScale) {
        if (rotaryCycle == null) {
            return;
        }
        if (completedCycles[ROTARY] >= rotaryCycle.cycleNumber) {
            rotaryCycle.completionConfirmed = true;
        }
        if (statuses[ROTARY] == FAULT_STATUS) {
            return;
        }

        double target = rotaryCycle.completionConfirmed ? 100.0 :
            (statuses[ROTARY] == BUSY_STATUS ? BUSY_HOLD_PERCENT :
                rotaryCycle.progress);
        rotaryCycle.progress = advanceToward(
            rotaryCycle.progress,
            target,
            frameScale
        );
        if (rotaryCycle.entryBottle != null) {
            rotaryCycle.entryBottle.progress = rotaryCycle.progress;
            rotaryCycle.entryBottle.lifecycle =
                rotaryCycle.completionConfirmed ?
                    BottleLifecycle.EXITING_STAGE :
                    BottleLifecycle.PROCESSING;
        }
        if (rotaryCycle.progress < 100.0 - COMPLETE_EPSILON) {
            return;
        }

        if (rotaryCycle.entryCycle) {
            rotaryStations[0] = rotaryCycle.entryBottle;
            rotaryCycle.entryBottle.progress = 100.0;
            rotaryCycle.entryBottle.lifecycle =
                BottleLifecycle.WAITING_FOR_HANDOFF;
            rotaryBottlesEntered++;
        }
        else {
            for (int station = ROTARY_STATION_COUNT - 1;
                station > 0;
                station--) {
                rotaryStations[station] = rotaryStations[station - 1];
            }
            rotaryStations[0] = null;
            rotaryBaseAngle = normaliseAngle(
                rotaryBaseAngle + ROTARY_STEP_DEGREES
            );
        }
        rotaryCycle = null;
    }

    private void finishVisuallyConfirmedBottle() {
        WorkState unloader = work[UNLOADER];
        if (unloader == null ||
            unloader.progress < 100.0 - COMPLETE_EPSILON ||
            realCompleted <= visualCompleted) {
            return;
        }
        unloader.bottle.progress = 100.0;
        unloader.bottle.lifecycle = BottleLifecycle.COMPLETED;
        traceModel(unloader.bottle, "COMPLETED");
        work[UNLOADER] = null;
        visualCompleted++;
    }

    private void confirmPreviousStage(int downstream) {
        if (downstream == FILLER_A) {
            if (rotaryCycle != null) {
                rotaryCycle.completionConfirmed = true;
                completedCycles[ROTARY] = Math.max(
                    completedCycles[ROTARY],
                    rotaryCycle.cycleNumber
                );
            }
            return;
        }
        int upstream = downstream == ROTARY ? CONVEYOR : downstream - 1;
        WorkState previous = work[upstream];
        if (previous != null) {
            previous.completionConfirmed = true;
            completedCycles[upstream] = Math.max(
                completedCycles[upstream],
                previous.cycleNumber
            );
        }
    }

    private void confirmCurrentCycle(int index) {
        if (index == ROTARY) {
            if (rotaryCycle != null &&
                completedCycles[index] >= rotaryCycle.cycleNumber) {
                rotaryCycle.completionConfirmed = true;
            }
        }
        else if (work[index] != null &&
            completedCycles[index] >= work[index].cycleNumber) {
            work[index].completionConfirmed = true;
        }
    }

    private void confirmUnloaderFromRealCount() {
        if (work[UNLOADER] != null && realCompleted > visualCompleted) {
            work[UNLOADER].completionConfirmed = true;
        }
    }

    /**
     * A completed-count increase proves that those bottles traversed every
     * stage, even when the one-second Coordinator polling missed short status
     * edges. Add only the minimum missing cycle evidence. Geometry still
     * advances through every stage using the same bounded interpolation.
     */
    private void backfillMissedCyclesFromCompletedCount() {
        int provenBottles = Math.min(realCompleted, bottles.size());
        if (provenBottles <= 0) {
            return;
        }
        for (int module = 0; module < MODULE_COUNT; module++) {
            int reached = bottlesAtOrBeyond(module, provenBottles);
            long missing = Math.max(0, provenBottles - reached);
            long pendingCycles = Math.max(
                0L,
                startedCycles[module] - claimedCycles[module]
            );
            long cyclesToAdd = Math.max(0L, missing - pendingCycles);
            if (cyclesToAdd > 0L) {
                startedCycles[module] += cyclesToAdd;
                completedCycles[module] = Math.max(
                    completedCycles[module],
                    startedCycles[module]
                );
            }
            if (module != ROTARY && work[module] != null &&
                work[module].bottle.displayId <= provenBottles) {
                work[module].completionConfirmed = true;
                completedCycles[module] = Math.max(
                    completedCycles[module],
                    work[module].cycleNumber
                );
            }
        }
        if (rotaryCycle != null &&
            (rotaryCycle.entryBottle == null ||
                rotaryCycle.entryBottle.displayId <= provenBottles)) {
            rotaryCycle.completionConfirmed = true;
            completedCycles[ROTARY] = Math.max(
                completedCycles[ROTARY],
                rotaryCycle.cycleNumber
            );
        }
    }

    private int bottlesAtOrBeyond(int module, int provenBottles) {
        int count = 0;
        for (BottleState bottle : bottles) {
            if (bottle.displayId <= provenBottles &&
                (bottle.stage >= module ||
                    bottle.lifecycle == BottleLifecycle.COMPLETED)) {
                count++;
            }
        }
        return count;
    }

    private double advanceToward(
        double current,
        double target,
        double frameScale
    ) {
        if (target <= current) {
            return current;
        }
        double remaining = target - current;
        double baseStep = Math.max(0.18, Math.min(1.20, remaining * 0.045));
        double step = baseStep * calculateCatchUpMultiplier() * frameScale;
        return remaining <= step ? target : current + step;
    }

    private double calculateCatchUpMultiplier() {
        int completionGap = Math.max(0, realCompleted - visualCompleted);
        long evidenceGap = 0L;
        for (int index = 0; index < MODULE_COUNT; index++) {
            evidenceGap += Math.max(0L,
                completedCycles[index] - claimedCycles[index]);
        }
        double multiplier = 1.0 + Math.min(1.2,
            completionGap * 0.40 + Math.min(0.40, evidenceGap * 0.08));
        return Math.min(MAX_CATCH_UP_MULTIPLIER, multiplier);
    }

    private void resetBatchNow(int newRequired) {
        required = newRequired;
        realCompleted = 0;
        visualCompleted = 0;
        nextBottleToAdmit = 0;
        pendingRequired = -1;
        batchTransitionTicks = 0;
        batchGeneration++;
        rotaryCycle = null;
        rotaryBottlesEntered = 0;
        rotaryBottlesExited = 0;
        rollerAngle = 0.0;
        rotaryBaseAngle = 0.0;
        bottles.clear();
        for (int index = 0; index < MODULE_COUNT; index++) {
            statuses[index] = 0;
            hasStatus[index] = false;
            cycleOpen[index] = false;
            startedCycles[index] = 0L;
            completedCycles[index] = 0L;
            claimedCycles[index] = 0L;
            work[index] = null;
        }
        for (int station = 0; station < rotaryStations.length; station++) {
            rotaryStations[station] = null;
        }
        for (int id = 1; id <= newRequired; id++) {
            bottles.add(new BottleState(id, batchGeneration));
        }
    }

    private void publish() {
        version++;
        ModuleSnapshot[] moduleSnapshots = new ModuleSnapshot[MODULE_COUNT];
        int[] stationBottleIds = new int[ROTARY_STATION_COUNT];
        for (int station = 0; station < stationBottleIds.length; station++) {
            stationBottleIds[station] = rotaryStations[station] == null ?
                0 : rotaryStations[station].displayId;
        }
        for (int index = 0; index < MODULE_COUNT; index++) {
            moduleSnapshots[index] = createModuleSnapshot(
                index,
                stationBottleIds
            );
        }
        List<BottleSnapshot> bottleSnapshots =
            new ArrayList<BottleSnapshot>(bottles.size());
        for (BottleState bottle : bottles) {
            bottleSnapshots.add(new BottleSnapshot(bottle));
        }
        published = new FlowSnapshot(
            version,
            batchGeneration,
            required,
            realCompleted,
            visualCompleted,
            determineMode(),
            moduleSnapshots,
            bottleSnapshots
        );
    }

    private ModuleSnapshot createModuleSnapshot(
        int index,
        int[] stationBottleIds
    ) {
        WorkState current = index == ROTARY ? null : work[index];
        double progress = current == null ? 0.0 : current.progress;
        int bottleId = current == null ? 0 : current.bottle.displayId;
        String rotaryPhase = "WAITING";
        double entryProgress = 0.0;
        double exitProgress = 0.0;
        double rotaryAngle = rotaryBaseAngle;

        if (index == ROTARY) {
            if (rotaryCycle != null) {
                progress = rotaryCycle.progress;
                bottleId = rotaryCycle.entryBottle == null ?
                    highestRotaryBottleId() :
                    rotaryCycle.entryBottle.displayId;
                if (rotaryCycle.entryCycle) {
                    rotaryPhase = "ENTRY";
                    entryProgress = progress / 100.0;
                }
                else {
                    rotaryPhase = progress < BUSY_HOLD_PERCENT ?
                        "ROTATING" : "SETTLING";
                    rotaryAngle = normaliseAngle(
                        rotaryBaseAngle + smoothStep(progress / 100.0) *
                            ROTARY_STEP_DEGREES
                    );
                }
            }
            else if (work[FILLER_A] != null &&
                work[FILLER_A].progress < 20.0) {
                rotaryPhase = "EXITING";
                exitProgress = work[FILLER_A].progress / 20.0;
                bottleId = work[FILLER_A].bottle.displayId;
            }
            else if (rotaryOccupiedCount() > 0) {
                rotaryPhase = "PROCESS_POSITION";
                bottleId = highestRotaryBottleId();
                progress = 100.0;
            }
        }

        ModuleLifecycle lifecycle = determineLifecycle(index, progress);
        boolean running = lifecycle == ModuleLifecycle.ENTERING ||
            lifecycle == ModuleLifecycle.ACTIVE ||
            lifecycle == ModuleLifecycle.EXITING;
        double liquidA = 0.0;
        double liquidB = 0.0;
        if (index == FILLER_A) {
            liquidA = smoothStep(progress / 100.0) * 60.0;
        }
        else if (index == FILLER_B) {
            liquidA = current == null ? 0.0 : 60.0;
            liquidB = smoothStep(progress / 100.0) * 40.0;
        }
        double tighteningAngle = index == CAPPER ?
            smoothStep(progress / 100.0) * 1080.0 : 0.0;

        return new ModuleSnapshot(
            version,
            index,
            progress,
            progress / 100.0,
            rollerAngle,
            rotaryAngle,
            entryProgress,
            exitProgress,
            stationBottleIds,
            rotaryBottlesEntered,
            rotaryBottlesExited,
            liquidA,
            liquidB,
            tighteningAngle,
            rotaryPhase,
            phaseName(index, progress, rotaryPhase),
            lifecycle,
            bottleId,
            running
        );
    }

    private ModuleLifecycle determineLifecycle(int index, double progress) {
        if (hasStatus[index] && statuses[index] == FAULT_STATUS) {
            return ModuleLifecycle.FAULTED;
        }
        boolean hasWork = index == ROTARY ?
            rotaryCycle != null || rotaryOccupiedCount() > 0 :
            work[index] != null;
        if (!hasWork) {
            return ModuleLifecycle.WAITING;
        }
        boolean confirmed = index == ROTARY ?
            rotaryCycle != null && rotaryCycle.completionConfirmed :
            work[index] != null && work[index].completionConfirmed;
        if (progress >= 100.0 - COMPLETE_EPSILON) {
            if (index == UNLOADER && realCompleted <= visualCompleted) {
                return ModuleLifecycle.HOLDING;
            }
            return confirmed ? ModuleLifecycle.COMPLETE :
                ModuleLifecycle.HOLDING;
        }
        if (confirmed) {
            return ModuleLifecycle.EXITING;
        }
        if (progress < 12.0) {
            return ModuleLifecycle.ENTERING;
        }
        if (progress >= BUSY_HOLD_PERCENT - COMPLETE_EPSILON) {
            return ModuleLifecycle.HOLDING;
        }
        return ModuleLifecycle.ACTIVE;
    }

    private String phaseName(int index, double progress, String rotaryPhase) {
        if (hasStatus[index] && statuses[index] == FAULT_STATUS) {
            return "FAULT HOLD - AWAITING NEW REAL STATE";
        }
        if (index == ROTARY) {
            return rotaryPhase;
        }
        if (progress <= 0.0) {
            return "WAITING FOR REAL START";
        }
        if (progress >= BUSY_HOLD_PERCENT && progress < 100.0) {
            return "WAITING FOR REAL COMPLETION";
        }
        if (index == LOADER) {
            return progress < 25.0 ? "QUEUE RELEASE" :
                (progress < 80.0 ? "SYMBOLIC DISPENSE" : "HANDOFF");
        }
        if (index == CONVEYOR) {
            return progress < 15.0 ? "ENTRY" :
                (progress < 85.0 ? "SYMBOLIC TRANSFER" : "EXIT");
        }
        if (index == FILLER_A) {
            return progress < 18.0 ? "NOZZLE APPROACH" :
                (progress < 82.0 ? "SYMBOLIC FILL A" : "NOZZLE RETRACT");
        }
        if (index == FILLER_B) {
            return progress < 18.0 ? "NOZZLE APPROACH" :
                (progress < 82.0 ? "SYMBOLIC FILL B" : "NOZZLE RETRACT");
        }
        if (index == LID) {
            return progress < 55.0 ? "SYMBOLIC LID PICK" :
                "SYMBOLIC LID PLACE";
        }
        if (index == CAPPER) {
            return progress < 30.0 ? "HEAD DESCENDING" :
                (progress < 72.0 ? "SYMBOLIC TIGHTENING" :
                    "HEAD ASCENDING");
        }
        return progress < 75.0 ? "SYMBOLIC UNLOAD" :
            "WAITING FOR COMPLETION CONFIRMATION";
    }

    private String determineMode() {
        if (batchTransitionTicks > 0) {
            return "BATCH TRANSITION";
        }
        boolean anyFault = false;
        boolean anyRunning = false;
        for (int index = 0; index < MODULE_COUNT; index++) {
            anyFault |= hasStatus[index] && statuses[index] == FAULT_STATUS;
            anyRunning |= index == ROTARY ? rotaryCycle != null :
                work[index] != null;
        }
        if (anyFault) {
            return "LOCAL FAULT HOLD";
        }
        if (calculateCatchUpMultiplier() > 1.0) {
            return "BOUNDED CATCH-UP";
        }
        if (anyRunning) {
            return "REAL-STATE ANCHORED";
        }
        if (required > 0 && visualCompleted >= required) {
            return "BATCH COMPLETE";
        }
        return required > 0 ? "WAITING FOR REAL START" : "IDLE";
    }

    private int highestOccupiedRotaryStation() {
        for (int station = ROTARY_STATION_COUNT - 1;
            station >= 0;
            station--) {
            if (rotaryStations[station] != null) {
                return station;
            }
        }
        return -1;
    }

    private int highestRotaryBottleId() {
        int station = highestOccupiedRotaryStation();
        return station < 0 ? 0 : rotaryStations[station].displayId;
    }

    private int rotaryOccupiedCount() {
        int occupied = 0;
        for (int station = 0; station < rotaryStations.length; station++) {
            if (rotaryStations[station] != null) {
                occupied++;
            }
        }
        return occupied;
    }

    private static double smoothStep(double value) {
        double bounded = Math.max(0.0, Math.min(1.0, value));
        return bounded * bounded * (3.0 - 2.0 * bounded);
    }

    private static double normaliseAngle(double value) {
        double normalised = value % 360.0;
        return normalised < 0.0 ? normalised + 360.0 : normalised;
    }

    private static void checkModuleIndex(int index) {
        if (index < 0 || index >= MODULE_COUNT) {
            throw new IllegalArgumentException("module index " + index);
        }
    }

    private static String moduleName(int index) {
        switch (index) {
            case LOADER:
                return "LOADER";
            case CONVEYOR:
                return "CONVEYOR";
            case ROTARY:
                return "ROTARY";
            case FILLER_A:
                return "FILLER_A";
            case FILLER_B:
                return "FILLER_B";
            case LID:
                return "LID";
            case CAPPER:
                return "CAPPER";
            case UNLOADER:
                return "UNLOADER";
            default:
                return "UNKNOWN";
        }
    }

    private static void traceModel(BottleState bottle, String stage) {
        if (TRACE_ENABLED) {
            System.out.println(
                "ABS_VIZ_MODEL batch=" + bottle.batchGeneration +
                " bottle=B" + bottle.displayId + " stage=" + stage
            );
        }
    }
}
