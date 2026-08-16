/** Timed physical Plant abstraction for lid pick and placement. */
public final class LidLoaderPlantModelV1 {
    public static final long PICK_TIME_MS = 300;
    public static final long PLACE_TIME_MS = 300;
    public static final long PLACED_SENSOR_HOLD_MS = 200;

    private enum Action {
        IDLE,
        PICKING,
        PICKED,
        PLACING
    }

    private Action action = Action.IDLE;
    private int magazineCount = 5;
    private long actionStartMs;
    private long placedSensorUntilMs;
    private boolean pickFault;
    private boolean placeFault;
    private boolean pickTriggerLatched;
    private boolean placeTriggerLatched;

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
            magazineCount--;
            placedSensorUntilMs = nowMs + PLACED_SENSOR_HOLD_MS;
        }
    }

    public void refill(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("refill count must be positive");
        }
        magazineCount += count;
    }

    public void setPickFault(boolean active) {
        pickFault = active;
        if (!active && action == Action.PICKING) {
            actionStartMs = System.currentTimeMillis();
        }
    }

    public void setPlaceFault(boolean active) {
        placeFault = active;
        if (!active && action == Action.PLACING) {
            actionStartMs = System.currentTimeMillis();
        }
    }

    public void cancelAction() {
        action = Action.IDLE;
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

    public String getActionName() {
        return action.name();
    }
}
