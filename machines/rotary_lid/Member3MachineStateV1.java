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
    private static long lastRotaryTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    private static long lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    private static boolean rotationDonePublished;
    private static boolean lidDonePublished;
    private static BoundedSignalOfferV1 lidDoneOffer =
        new BoundedSignalOfferV1(3);
    private static long nextCycleId = 1;
    private static String pendingRotaryTestFault;
    private static LidLoaderControllerModelV1.Fault pendingLidTestFault;

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
        if (M3SystemResetStateV1.isQuarantined() ||
            FaultSupervisorStateV2_1.isOperationHeld()) {
            return false;
        }
        boolean started = rotary.requestRotation(
            nextCycleId,
            stationBarrierSatisfied
        );
        if (started) {
            nextCycleId++;
            lastRotaryTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            if (pendingRotaryTestFault != null &&
                rotary.injectFault(pendingRotaryTestFault)) {
                FaultInjectionStateV2_1.consumed(pendingRotaryTestFault);
                pendingRotaryTestFault = null;
                reportRotaryFaultIfPresent();
            }
        }
        return started;
    }

    public static synchronized void tickRotary(
        long elapsedMs,
        boolean tableAlignedWithSensor
    ) {
        rotary.tick(elapsedMs, tableAlignedWithSensor);
        reportRotaryFaultIfPresent();
        recordRotaryHeartbeat();
    }

    public static synchronized void tickRotaryNow(
        boolean tableAlignedWithSensor
    ) {
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        rotary.tick(Math.max(0, now - lastRotaryTickMs),
            tableAlignedWithSensor);
        lastRotaryTickMs = now;
        reportRotaryFaultIfPresent();
        recordRotaryHeartbeat();
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
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
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
        if (M3SystemResetStateV1.isQuarantined() ||
            FaultSupervisorStateV2_1.isOperationHeld()) {
            return false;
        }
        if (lidLoader.getStatus() == DONE) {
            return false;
        }
        boolean started = lidLoader.requestLoad(
            bottleId,
            lidAvailable
        );
        if (started) {
            lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            if (pendingLidTestFault != null &&
                lidLoader.injectFault(pendingLidTestFault)) {
                FaultInjectionStateV2_1.consumed(pendingLidTestFault.name());
                pendingLidTestFault = null;
            }
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
        recordLidHeartbeat();
    }

    public static synchronized void tickLidLoaderNow(
        boolean lidPicked,
        boolean lidPlaced
    ) {
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        lidLoader.tick(Math.max(0, now - lastLidTickMs),
            lidPicked, lidPlaced);
        lastLidTickMs = now;
        reportLidFaultIfPresent();
        recordLidHeartbeat();
    }

    public static synchronized boolean takeLidDoneEvent() {
        return takeLidDoneBottleId() != null;
    }

    public static synchronized String takeLidDoneBottleId() {
        if (lidLoader.getStatus() != DONE) {
            return null;
        }
        if (!lidDonePublished) {
            String bottleId = lidLoader.takeCompletedBottleId();
            if (bottleId != null) {
                lidDoneOffer.arm(bottleId, bottleId);
                lidDonePublished = true;
            }
        }
        return lidDoneOffer.nextReactionValue();
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
            lidDoneOffer = new BoundedSignalOfferV1(3);
        }
        return acknowledged;
    }

    public static synchronized boolean resetLidFault(
        LidRecoveryEvidenceV1 evidence
    ) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
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

    public static synchronized boolean armTestFault(String faultCode) {
        if (pendingRotaryTestFault != null || pendingLidTestFault != null ||
            rotary.getStatus() == FAULT || lidLoader.getStatus() == FAULT) {
            return false;
        }
        if ("ALIGNMENT_TIMEOUT".equals(faultCode) ||
            "MOTOR_STALL".equals(faultCode) ||
            "POSITION_SENSOR_FAILURE".equals(faultCode)) {
            pendingRotaryTestFault = faultCode;
            return true;
        }
        try {
            LidLoaderControllerModelV1.Fault fault =
                LidLoaderControllerModelV1.Fault.valueOf(faultCode);
            if (fault != LidLoaderControllerModelV1.Fault.NONE) {
                pendingLidTestFault = fault;
                return true;
            }
        }
        catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    public static synchronized boolean recoverActiveTestFault() {
        if (rotary.getStatus() == FAULT) {
            return resetRotaryFault(new RotaryRecoveryEvidenceV1(
                true, true, true
            ));
        }
        if (lidLoader.getStatus() == FAULT) {
            return resetLidFault(new LidRecoveryEvidenceV1(
                true, true, true, true, true
            ));
        }
        return true;
    }

    /** Restores deterministic state before a simulation or test run. */
    public static synchronized void reset() {
        rotary = new RotaryControllerModelV1();
        lidLoader = new LidLoaderControllerModelV1();
        lastRotaryTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        rotationDonePublished = false;
        lidDonePublished = false;
        lidDoneOffer = new BoundedSignalOfferV1(3);
        nextCycleId = 1;
        pendingRotaryTestFault = null;
        pendingLidTestFault = null;
        FaultInjectionStateV2_1.reset();
        FaultSupervisorStateV2_1.reset();
    }

    /** Safe production reset without reusing cycle or fault identities. */
    public static synchronized void systemReset() {
        long rotaryFaultSequence = rotary.getFaultSequence();
        long lidFaultSequence = lidLoader.getFaultSequence();
        rotary = new RotaryControllerModelV1(rotaryFaultSequence);
        lidLoader = new LidLoaderControllerModelV1(lidFaultSequence);
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime()
        );
        lastRotaryTickMs = now;
        lastLidTickMs = now;
        rotationDonePublished = false;
        lidDonePublished = false;
        lidDoneOffer = new BoundedSignalOfferV1(3);
        pendingRotaryTestFault = null;
        pendingLidTestFault = null;
        FaultInjectionStateV2_1.reset();
        FaultSupervisorStateV2_1.systemReset();
    }

    public static synchronized boolean isResetSafe() {
        return rotary.getStatus() == READY && !rotary.isMotorEnabled() &&
            lidLoader.getStatus() == READY &&
            !lidLoader.isPickActuatorEnabled() &&
            !lidLoader.isPlaceActuatorEnabled();
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
                rotary.getFaultCode(),
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

    private static void recordRotaryHeartbeat() {
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.ROTARY_CONTROLLER,
            rotary.getStatus() != FAULT,
            statusName(rotary.getStatus())
        );
    }

    private static void recordLidHeartbeat() {
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.LID_CONTROLLER,
            lidLoader.getStatus() != FAULT,
            statusName(lidLoader.getStatus())
        );
    }

}
