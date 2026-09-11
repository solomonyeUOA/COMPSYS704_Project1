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
    private static long nextCycleId = 1;

    private Member3MachineStateV1() {
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getRotaryStatus() {
        return rotary.getStatus();
    }

    /** Compatibility alias for older local tests; not a V2.1 interface name. */
    public static synchronized int getTransportStatus() {
        return getRotaryStatus();
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getLidStatus() {
        return lidLoader.getStatus();
    }

    public static synchronized boolean requestRotation(
        boolean stationBarrierSatisfied
    ) {
        boolean started = rotary.requestRotation(
            nextCycleId,
            stationBarrierSatisfied
        );
        if (started) {
            nextCycleId++;
            lastRotaryTickMs = System.currentTimeMillis();
        }
        return started;
    }

    public static synchronized void tickRotary(
        long elapsedMs,
        boolean tableAlignedWithSensor
    ) {
        rotary.tick(elapsedMs, tableAlignedWithSensor);
        reportRotaryFaultIfPresent();
    }

    public static synchronized void tickRotaryNow(
        boolean tableAlignedWithSensor
    ) {
        long now = System.currentTimeMillis();
        rotary.tick(Math.max(0, now - lastRotaryTickMs),
            tableAlignedWithSensor);
        lastRotaryTickMs = now;
        reportRotaryFaultIfPresent();
    }

    public static synchronized boolean takeRotationDoneEvent() {
        if (rotary.getStatus() != DONE || rotationDonePublished) {
            return false;
        }
        rotationDonePublished = true;
        return true;
    }

    public static synchronized boolean acknowledgeRotationDone() {
        boolean acknowledged = rotary.acknowledgeDone();
        if (acknowledged) {
            rotationDonePublished = false;
        }
        return acknowledged;
    }

    public static synchronized boolean resetRotaryFault(
        RotaryRecoveryEvidenceV1 evidence
    ) {
        String eventId = rotary.getFaultEventId();
        if (!FaultSupervisorStateV2_1.authorizeRotaryReset(
            eventId,
            evidence
        )) {
            return false;
        }
        boolean reset = rotary.resetFault(evidence);
        if (reset) {
            FaultSupervisorStateV2_1.resolveLocalFault("ROTARY", eventId);
        }
        return reset;
    }

    public static synchronized boolean isRotaryMotorEnabled() {
        return rotary.isMotorEnabled();
    }

    public static synchronized boolean requestLidLoad(
        String bottleId,
        boolean lidAvailable
    ) {
        if (lidLoader.getStatus() == DONE) {
            return false;
        }
        boolean started = lidLoader.requestLoad(
            bottleId,
            lidAvailable
        );
        if (started) {
            lastLidTickMs = System.currentTimeMillis();
        }
        reportLidFaultIfPresent();
        return started;
    }

    public static synchronized void tickLidLoader(
        long elapsedMs,
        boolean lidPicked,
        boolean lidPlaced
    ) {
        lidLoader.tick(elapsedMs, lidPicked, lidPlaced);
        reportLidFaultIfPresent();
    }

    public static synchronized void tickLidLoaderNow(
        boolean lidPicked,
        boolean lidPlaced
    ) {
        long now = System.currentTimeMillis();
        lidLoader.tick(Math.max(0, now - lastLidTickMs),
            lidPicked, lidPlaced);
        lastLidTickMs = now;
        reportLidFaultIfPresent();
    }

    public static synchronized boolean takeLidDoneEvent() {
        return takeLidDoneBottleId() != null;
    }

    public static synchronized String takeLidDoneBottleId() {
        if (lidLoader.getStatus() != DONE || lidDonePublished) {
            return null;
        }
        String bottleId = lidLoader.takeCompletedBottleId();
        if (bottleId != null) {
            lidDonePublished = true;
        }
        return bottleId;
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

    public static synchronized boolean resetLidFault(
        LidRecoveryEvidenceV1 evidence
    ) {
        String eventId = lidLoader.getFaultEventId();
        LidLoaderControllerModelV1.Fault fault = lidLoader.getFault();
        if (!FaultSupervisorStateV2_1.authorizeLidReset(
            eventId,
            fault,
            evidence
        )) {
            return false;
        }
        boolean reset = lidLoader.resetFault(evidence);
        if (reset) {
            FaultSupervisorStateV2_1.resolveLocalFault("LID", eventId);
        }
        return reset;
    }

    /** Restores deterministic state before a simulation or test run. */
    public static synchronized void reset() {
        rotary = new RotaryControllerModelV1();
        lidLoader = new LidLoaderControllerModelV1();
        lastRotaryTickMs = System.currentTimeMillis();
        lastLidTickMs = System.currentTimeMillis();
        rotationDonePublished = false;
        lidDonePublished = false;
        nextCycleId = 1;
        FaultSupervisorStateV2_1.reset();
    }

    public static synchronized long getActiveCycleId() {
        return rotary.getActiveCycleId();
    }

    public static synchronized long getLastCompletedCycleId() {
        return rotary.getLastCompletedCycleId();
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

    private static void reportRotaryFaultIfPresent() {
        if (rotary.getStatus() == FAULT) {
            FaultSupervisorStateV2_1.observeRotaryFault(
                rotary.getFaultEventId(),
                rotary.getFaultReason()
            );
        }
    }

    private static void reportLidFaultIfPresent() {
        if (lidLoader.getStatus() == FAULT) {
            FaultSupervisorStateV2_1.observeLidFault(
                lidLoader.getFaultEventId(),
                lidLoader.getFault()
            );
        }
    }

}
