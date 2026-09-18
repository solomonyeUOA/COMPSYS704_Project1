/** Timed physical Plant abstraction for lid pick and placement. */
public final class LidLoaderPlantModelV1 {
    public static final long PICK_TIME_MS = SimulationTiming.scaleMillis(300L);
    public static final long PLACE_TIME_MS = SimulationTiming.scaleMillis(300L);
    public static final long PLACED_SENSOR_HOLD_MS = 200;

    /** Finite simulation capacity, independent of the submitted order size. */
    public static final int MAGAZINE_CAPACITY = 9999;

    private enum Action {
        IDLE,
        PICKING,
        PICKED,
        PLACING
    }

    private Action action = Action.IDLE;
    private final RedundantDriveV1 pickDrive;
    private final RedundantDriveV1 placeDrive;
    public RedundantDriveV1 pickDrive() { return pickDrive; }
    public RedundantDriveV1 placeDrive() { return placeDrive; }
    private final int magazineCapacity;
    private int magazineCount;
    private long actionStartMs;
    private long lastMotionTickMs;
    private long placedSensorUntilMs;
    private boolean pickFault;
    private boolean placeFault;
    private boolean pickTriggerLatched;
    private boolean placeTriggerLatched;
    private boolean lidHeld;
    private boolean loadLost;
    private long completedPlacements;
    public long completedPlacements() { return completedPlacements; }
    public void loseHeldLid() { loadLost = true; lidHeld = false; }
    public String physicalSnapshot() {
        return "action=" + action + " lidHeld=" + lidHeld + " loadLost=" + loadLost;
    }

    public LidLoaderPlantModelV1() {
        this(MAGAZINE_CAPACITY);
    }

    public LidLoaderPlantModelV1(int initialMagazineCount) {
        this(initialMagazineCount, new RedundantDriveV1("LID PICK DRIVE"),
            new RedundantDriveV1("LID PLACE DRIVE"));
    }

    LidLoaderPlantModelV1(int initialMagazineCount, RedundantDriveV1 pick,
        RedundantDriveV1 place) {
        pickDrive = pick;
        placeDrive = place;
        if (MAGAZINE_CAPACITY <= 0) {
            throw new IllegalStateException("lid magazine geometry gives no usable capacity");
        }
        magazineCapacity = MAGAZINE_CAPACITY;
        magazineCount = Math.max(0, Math.min(
            initialMagazineCount, magazineCapacity
        ));
    }

    public boolean setPickCommand(boolean enabled, long nowMs) {
        boolean started = false;
        if (enabled && !pickTriggerLatched && action == Action.IDLE &&
            magazineCount > 0) {
            action = Action.PICKING;
            actionStartMs = nowMs;
            lastMotionTickMs = nowMs;
            started = true;
        }
        pickTriggerLatched = enabled;
        return started;
    }

    public boolean setPlaceCommand(boolean enabled, long nowMs) {
        boolean started = false;
        if (enabled && !placeTriggerLatched && action == Action.PICKED) {
            action = Action.PLACING;
            actionStartMs = nowMs;
            lastMotionTickMs = nowMs;
            started = true;
        }
        placeTriggerLatched = enabled;
        return started;
    }

    public void tick(long nowMs) {
        RedundantDriveV1 current = action == Action.PICKING ? pickDrive :
            action == Action.PLACING ? placeDrive : null;
        if (current != null) {
            if (loadLost || (action == Action.PLACING && !lidHeld)) current.inhibit("LID_NOT_RETAINED");
            int previous = current.switchCount();
            boolean permitted = current.permitMotion(!pickFault && !placeFault, nowMs);
            if (!permitted || previous != current.switchCount())
                actionStartMs += Math.max(0L, nowMs - lastMotionTickMs);
            lastMotionTickMs = nowMs;
            if (!permitted) return;
        }
        if (action == Action.PICKING && !pickFault &&
            nowMs - actionStartMs >= PICK_TIME_MS) {
            action = Action.PICKED;
            lidHeld = true;
            pickDrive.completed();
        }
        else if (action == Action.PLACING && !placeFault &&
            nowMs - actionStartMs >= PLACE_TIME_MS) {
            action = Action.IDLE;
            if (magazineCount <= 0) {
                throw new IllegalStateException("completed placement without magazine inventory");
            }
            magazineCount--;
            completedPlacements++;
            lidHeld = false;
            placedSensorUntilMs = nowMs + PLACED_SENSOR_HOLD_MS;
            placeDrive.completed();
        }
    }

    public void pauseMotion(long nowMs) {
        if (action == Action.PICKING || action == Action.PLACING)
            actionStartMs += Math.max(0L, nowMs - lastMotionTickMs);
        lastMotionTickMs = nowMs;
    }

    public int refill(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("refill count must be positive");
        }
        int accepted = Math.min(count, magazineCapacity - magazineCount);
        magazineCount += accepted;
        return accepted;
    }

    public void setPickFault(boolean active) {
        pickFault = active;
        if (!active && action == Action.PICKING) {
            actionStartMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    }

    public void setPlaceFault(boolean active) {
        placeFault = active;
        if (!active && action == Action.PLACING) {
            actionStartMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    }

    public void cancelAction() {
        pickDrive.interruptAction();
        placeDrive.interruptAction();
        action = Action.IDLE;
        lidHeld = false;
        actionStartMs = 0L;
        placedSensorUntilMs = 0L;
        pickFault = false;
        placeFault = false;
        pickTriggerLatched = false;
        placeTriggerLatched = false;
    }

    public boolean isLidAvailable() {
        return magazineCount > 0;
    }

    public boolean isLidPicked() {
        return lidHeld && (action == Action.PICKED || action == Action.PLACING);
    }

    public boolean isLidPlacedSensorActive(long nowMs) {
        return nowMs <= placedSensorUntilMs;
    }

    public int getMagazineCount() {
        return magazineCount;
    }

    public int getMagazineCapacity() {
        return magazineCapacity;
    }

    public String getActionName() {
        return action.name();
    }

    public boolean isActuatorHome() {
        return action == Action.IDLE;
    }

    public boolean isNoLidHeld() {
        return !lidHeld;
    }

    public boolean isPlacementSensorHealthy() {
        return !placeFault;
    }
}
