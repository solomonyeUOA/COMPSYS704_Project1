/** Shared Coordinator-facing state for the Member 3 machine controllers. */
public final class Member3MachineStateV1 {
    public static final int IDLE = 0;
    public static final int READY = 1;
    public static final int BUSY = 2;
    public static final int DONE = 3;
    public static final int FAULT = 4;

    private static RotaryControllerModelV1 rotary =
        new RotaryControllerModelV1();
    private static LidLoaderControllerModelV1 lidLoader =
        new LidLoaderControllerModelV1();
    private static long lastRotaryTickMs = System.currentTimeMillis();
    private static long lastLidTickMs = System.currentTimeMillis();
    private static boolean rotationDonePublished;
    private static boolean lidDonePublished;

    private Member3MachineStateV1() {
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getTransportStatus() {
        return rotary.getStatus();
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getLidStatus() {
        return lidLoader.getStatus();
    }

    public static synchronized boolean requestRotation(
        boolean capOnBottleAtPosition1
    ) {
        if (rotary.getStatus() == DONE) {
            rotary.acknowledgeDone();
            rotationDonePublished = false;
        }
        boolean started = rotary.requestRotation(capOnBottleAtPosition1);
        if (started) {
            lastRotaryTickMs = System.currentTimeMillis();
        }
        return started;
    }

    public static synchronized void tickRotary(
        long elapsedMs,
        boolean tableAlignedWithSensor
    ) {
        rotary.tick(elapsedMs, tableAlignedWithSensor);
    }

    public static synchronized void tickRotaryNow(
        boolean tableAlignedWithSensor
    ) {
        long now = System.currentTimeMillis();
        rotary.tick(Math.max(0, now - lastRotaryTickMs),
            tableAlignedWithSensor);
        lastRotaryTickMs = now;
    }

    public static synchronized boolean takeRotationDoneEvent() {
        if (rotary.getStatus() != DONE || rotationDonePublished) {
            return false;
        }
        rotationDonePublished = true;
        return true;
    }

    public static synchronized boolean acknowledgeRotationDone() {
        return rotary.acknowledgeDone();
    }

    public static synchronized boolean resetRotaryFault(
        boolean tableAlignedWithSensor
    ) {
        return rotary.resetFault(tableAlignedWithSensor);
    }

    public static synchronized boolean isRotaryMotorEnabled() {
        return rotary.isMotorEnabled();
    }

    public static synchronized boolean requestLidLoad(
        boolean bottleAtLidPosition,
        boolean lidAvailable
    ) {
        if (lidLoader.getStatus() == DONE) {
            return false;
        }
        boolean started = lidLoader.requestLoad(
            bottleAtLidPosition,
            lidAvailable
        );
        if (started) {
            lastLidTickMs = System.currentTimeMillis();
        }
        return started;
    }

    public static synchronized void tickLidLoader(
        long elapsedMs,
        boolean lidPicked,
        boolean lidPlaced
    ) {
        lidLoader.tick(elapsedMs, lidPicked, lidPlaced);
    }

    public static synchronized void tickLidLoaderNow(
        boolean lidPicked,
        boolean lidPlaced
    ) {
        long now = System.currentTimeMillis();
        lidLoader.tick(Math.max(0, now - lastLidTickMs),
            lidPicked, lidPlaced);
        lastLidTickMs = now;
    }

    public static synchronized boolean takeLidDoneEvent() {
        if (lidLoader.getStatus() != DONE || lidDonePublished) {
            return false;
        }
        lidDonePublished = true;
        return true;
    }

    public static synchronized boolean isLidPickEnabled() {
        return lidLoader.isPickActuatorEnabled();
    }

    public static synchronized boolean isLidPlaceEnabled() {
        return lidLoader.isPlaceActuatorEnabled();
    }

    public static synchronized boolean acknowledgeLidDone() {
        boolean acknowledged = lidLoader.acknowledgeDone();
        if (acknowledged) {
            lidDonePublished = false;
        }
        return acknowledged;
    }

    public static synchronized boolean resetLidFault(boolean lidAvailable) {
        return lidLoader.resetFault(lidAvailable);
    }

    /** Restores deterministic state before a simulation or test run. */
    public static synchronized void reset() {
        rotary = new RotaryControllerModelV1();
        lidLoader = new LidLoaderControllerModelV1();
        lastRotaryTickMs = System.currentTimeMillis();
        lastLidTickMs = System.currentTimeMillis();
        rotationDonePublished = false;
        lidDonePublished = false;
    }

    public static String statusName(int status) {
        switch (status) {
            case IDLE:
                return "IDLE";
            case READY:
                return "READY";
            case BUSY:
                return "BUSY";
            case DONE:
                return "DONE";
            case FAULT:
                return "FAULT";
            default:
                return "UNKNOWN";
        }
    }

}
