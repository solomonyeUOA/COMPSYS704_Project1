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
    private final int magazineCapacity;
    private int magazineCount;
    private long actionStartMs;
    private long placedSensorUntilMs;
    private boolean pickFault;
    private boolean placeFault;
    private boolean pickTriggerLatched;
    private boolean placeTriggerLatched;

    public LidLoaderPlantModelV1() {
        this(MAGAZINE_CAPACITY);
    }

    public LidLoaderPlantModelV1(int initialMagazineCount) {
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
            started = true;
        }
        placeTriggerLatched = enabled;
        return started;
    }

    public void tick(long nowMs) {
        if (action == Action.PICKING && !pickFault &&
            nowMs - actionStartMs >= PICK_TIME_MS) {
            action = Action.PICKED;
        }
        else if (action == Action.PLACING && !placeFault &&
            nowMs - actionStartMs >= PLACE_TIME_MS) {
            action = Action.IDLE;
            if (magazineCount <= 0) {
                throw new IllegalStateException("completed placement without magazine inventory");
            }
            magazineCount--;
            placedSensorUntilMs = nowMs + PLACED_SENSOR_HOLD_MS;
        }
    }

    /** Returns the number of lids accepted without exceeding physical capacity. */
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
        action = Action.IDLE;
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
        return action == Action.PICKED || action == Action.PLACING;
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
        return action == Action.IDLE || action == Action.PICKING;
    }

    public boolean isPlacementSensorHealthy() {
        return !placeFault;
    }
}
