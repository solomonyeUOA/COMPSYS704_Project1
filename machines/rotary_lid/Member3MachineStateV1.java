/** Shared Coordinator-facing state for the Member 3 machine controllers. */
public final class Member3MachineStateV1 {
    public static final int IDLE = 0;
    public static final int READY = 1;
    public static final int BUSY = 2;
    public static final int DONE = 3;
    public static final int FAULT = 4;

    private static volatile RotaryControllerModelV1 rotary =
        new RotaryControllerModelV1();
    private static volatile LidLoaderControllerModelV1 lidLoader =
        new LidLoaderControllerModelV1();
    private static long lastRotaryTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    private static long lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    private static boolean rotationDonePublished;
    private static boolean lidDonePublished;
    private static BoundedSignalOfferV1 lidDoneOffer =
        new BoundedSignalOfferV1(3);
    private static long nextCycleId = 1;
    // GUI calls must not acquire the machine monitor while holding the injection monitor.
    private static final java.util.concurrent.atomic.AtomicReference<String> machineInjection =
        new java.util.concurrent.atomic.AtomicReference<String>();
    private static ControllerStandbyV1 rotaryPair = new ControllerStandbyV1("ROTARY CONTROLLER");
    private static ControllerStandbyV1 lidPair = new ControllerStandbyV1("LID CONTROLLER");
    private static RotaryControllerModelV1 rotaryCheckpoint;
    private static LidLoaderControllerModelV1 lidCheckpoint;
    private static final java.util.concurrent.atomic.AtomicReference<String> controllerInjection =
        new java.util.concurrent.atomic.AtomicReference<String>();
    private static volatile String controllerTelemetry = "|ROTARY_CTRL,A,false,false,AVAILABLE,0|LID_CTRL,A,false,false,AVAILABLE,0";

    public static String controllerTelemetry() { return controllerTelemetry; }
    private static void publishControllerTelemetry() {
        controllerTelemetry = "|ROTARY_CTRL," + rotaryPair.telemetry() + "|LID_CTRL," + lidPair.telemetry();
    }

    public static boolean armControllerFailure(String code) {
        return controllerInjection.compareAndSet(null, code);
    }

    public static boolean cancelControllerFailure(String code) {
        String pending = controllerInjection.get();
        return code != null && code.equals(pending) && controllerInjection.compareAndSet(pending, null);
    }

    public static synchronized String controllerRedundancySnapshot() {
        return rotaryPair.snapshot() + "\n" + lidPair.snapshot();
    }

    public static synchronized FaultMonitoringStateV2_1.ComponentSnapshot[] controllerMonitoringSnapshot() {
        ControllerStandbyV1[] pairs = {rotaryPair, lidPair};
        String[] names = {"ROTARY CONTROLLER PAIR", "LID CONTROLLER PAIR"};
        FaultMonitoringStateV2_1.ComponentSnapshot[] result = new FaultMonitoringStateV2_1.ComponentSnapshot[2];
        for (int i = 0; i < pairs.length; i++) {
            String state = pairs[i].telemetry().split(",")[3];
            result[i] = new FaultMonitoringStateV2_1.ComponentSnapshot(names[i], "M3", state,
                "LOCAL CONTROLLER PAIR", -1, -1, pairs[i].snapshot());
        }
        return result;
    }

    private static boolean prepareController(boolean isRotary) {
        ControllerStandbyV1 pair = isRotary ? rotaryPair : lidPair;
        if (pair.available()) {
            if (isRotary) rotaryCheckpoint = new RotaryControllerModelV1(rotary);
            else lidCheckpoint = new LidLoaderControllerModelV1(lidLoader);
            pair.checkpoint();
        }
        String code = isRotary ? "ROTARY_CONTROLLER_FAILURE" : "LID_CONTROLLER_FAILURE";
        if (cancelControllerFailure(code)) {
            pair.failActive();
            FaultInjectionStateV2_1.consumed(code);
        }
        if (pair.takeover(SystemWatchdogV1.isDriveFailoverEnabled())) {
            if (isRotary) rotary = new RotaryControllerModelV1(rotaryCheckpoint);
            else lidLoader = new LidLoaderControllerModelV1(lidCheckpoint);
        }
        publishControllerTelemetry();
        return pair.available();
    }

    private Member3MachineStateV1() {
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getRotaryStatus() {
        return rotaryPair.available() ? rotary.getStatus() : FAULT;
    }

    /** Compatibility alias for older local tests; not a V2.1 interface name. */
    public static synchronized int getTransportStatus() {
        return getRotaryStatus();
    }

    /** Status polling is observational and must never advance a machine. */
    public static synchronized int getLidStatus() {
        if (lidLoader.isRetryingPick() && lidLoader.getStatus() == DONE &&
            FaultSupervisorStateV2_1.isOperationHeld()) return BUSY;
        return lidPair.available() ? lidLoader.getStatus() : FAULT;
    }

    public static synchronized String[] pickRecoveryState() {
        return new String[] {lidLoader.getFaultEventId(), lidLoader.getActiveBottleId(),
            lidLoader.getState().name(), lidLoader.getFault().name()};
    }

    public static synchronized boolean startPickRetry(String event, String epoch, String bottle) {
        if (!SystemWatchdogV1.isDriveFailoverEnabled() || !lidPair.available() ||
            M3SystemResetStateV1.isQuarantined() || !event.equals(FaultSupervisorStateV2_1.activeEventId()) ||
            !epoch.equals(FaultSupervisorStateV2_1.activeEpoch()) ||
            !"WAITING_RESULT".equals(FaultSupervisorStateV2_1.stateName()) ||
            !bottle.equals(lidLoader.getActiveBottleId())) return false;
        boolean started = lidLoader.retryPick(event);
        if (started) lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        return started;
    }

    public static synchronized boolean requestRotation(
        boolean stationBarrierSatisfied
    ) {
        if (!rotaryPair.available() || M3SystemResetStateV1.isQuarantined() ||
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
            String code = machineInjection.get();
            if (isRotaryTestFault(code) && machineInjection.compareAndSet(code, null)) {
                if ("POSITION_SENSOR_FAILURE".equals(code)) Member3PlantStateV1.queueRotaryFeedbackFailure();
                else rotary.injectFault(code);
                FaultInjectionStateV2_1.consumed(code);
                reportRotaryFaultIfPresent();
            }
        }
        return started;
    }

    public static synchronized void tickRotary(
        long elapsedMs,
        boolean tableAlignedWithSensor
    ) {
        if (!prepareController(true)) return;
        rotary.tick(elapsedMs, tableAlignedWithSensor);
        if (rotary.getStatus() == DONE) rotaryPair.completed();
        publishControllerTelemetry();
        reportRotaryFaultIfPresent();
        recordRotaryHeartbeat();
    }

    public static synchronized void tickRotaryNow(
        boolean tableAlignedWithSensor
    ) {
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        tickRotary(Math.max(0, now - lastRotaryTickMs), tableAlignedWithSensor);
        lastRotaryTickMs = now;
        reportRotaryFaultIfPresent();
        recordRotaryHeartbeat();
    }

    public static synchronized boolean takeRotationDoneEvent() {
        if (!rotaryPair.available() || rotary.getStatus() != DONE || rotationDonePublished) {
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



    public static synchronized boolean isRotaryMotorEnabled() {
        return rotaryPair.available() && rotary.isMotorEnabled();
    }

    public static synchronized boolean requestLidLoad(
        String bottleId,
        boolean lidAvailable
    ) {
        if (!lidPair.available() || M3SystemResetStateV1.isQuarantined() ||
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
            String code = machineInjection.get();
            if (code != null && !isRotaryTestFault(code) && machineInjection.compareAndSet(code, null)) {
                lidLoader.injectFault(LidLoaderControllerModelV1.Fault.valueOf(code));
                FaultInjectionStateV2_1.consumed(code);
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
        if (!prepareController(false)) return;
        if (lidLoader.isRetryingPick() && !FaultSupervisorStateV2_1.permitsLocalPickMotion()) {
            recordLidHeartbeat();
            return;
        }
        lidLoader.tick(elapsedMs, lidPicked, lidPlaced);
        if (lidLoader.getStatus() == DONE) lidPair.completed();
        publishControllerTelemetry();
        reportLidFaultIfPresent();
        recordLidHeartbeat();
    }

    public static synchronized void tickLidLoaderNow(
        boolean lidPicked,
        boolean lidPlaced
    ) {
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        tickLidLoader(Math.max(0, now - lastLidTickMs), lidPicked, lidPlaced);
        lastLidTickMs = now;
        reportLidFaultIfPresent();
        recordLidHeartbeat();
    }

    public static synchronized boolean takeLidDoneEvent() {
        return takeLidDoneBottleId() != null;
    }

    public static synchronized String takeLidDoneBottleId() {
        if (lidLoader.isRetryingPick() && FaultSupervisorStateV2_1.isOperationHeld()) return null;
        if (!lidPair.available() || lidLoader.getStatus() != DONE) {
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
        return lidPair.available() && FaultSupervisorStateV2_1.permitsLocalPickMotion() && lidLoader.isPickActuatorEnabled();
    }

    public static synchronized boolean isLidPlaceEnabled() {
        return lidPair.available() && FaultSupervisorStateV2_1.permitsLocalPickMotion() && lidLoader.isPlaceActuatorEnabled();
    }

    public static synchronized boolean acknowledgeLidDone() {
        if (lidLoader.isRetryingPick() && FaultSupervisorStateV2_1.isOperationHeld()) return false;
        boolean acknowledged = lidLoader.acknowledgeDone();
        if (acknowledged) {
            lidDonePublished = false;
            lidDoneOffer = new BoundedSignalOfferV1(3);
        }
        return acknowledged;
    }



    public static boolean armTestFault(String faultCode) {
        if (faultCode == null || rotary.getStatus() == FAULT || lidLoader.getStatus() == FAULT) {
            return false;
        }
        if (isRotaryTestFault(faultCode)) {
            return machineInjection.compareAndSet(null, faultCode);
        }
        try {
            LidLoaderControllerModelV1.Fault fault =
                LidLoaderControllerModelV1.Fault.valueOf(faultCode);
            if (fault != LidLoaderControllerModelV1.Fault.NONE) {
                return machineInjection.compareAndSet(null, faultCode);
            }
        }
        catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    public static boolean cancelArmedTestFault(String faultCode) {
        String pending = machineInjection.get();
        return faultCode != null && faultCode.equals(pending) && machineInjection.compareAndSet(pending, null);
    }

    public static void clearPendingTestFaults() {
        machineInjection.set(null);
        controllerInjection.set(null);
    }

    private static boolean isRotaryTestFault(String code) {
        return "ALIGNMENT_TIMEOUT".equals(code) || "MOTOR_STALL".equals(code) ||
            "POSITION_SENSOR_FAILURE".equals(code);
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

    /** Restores deterministic state before a simulation or test run. */
    public static synchronized void reset() {
        rotaryPair = new ControllerStandbyV1("ROTARY CONTROLLER");
        lidPair = new ControllerStandbyV1("LID CONTROLLER");
        controllerInjection.set(null);
        publishControllerTelemetry();
        rotary = new RotaryControllerModelV1();
        lidLoader = new LidLoaderControllerModelV1();
        lastRotaryTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        lastLidTickMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        rotationDonePublished = false;
        lidDonePublished = false;
        lidDoneOffer = new BoundedSignalOfferV1(3);
        nextCycleId = 1;
        clearPendingTestFaults();
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
        clearPendingTestFaults();
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
