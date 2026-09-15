/** Shared Plant state used by the Member 3 SystemJ Plant clock-domains. */
public final class Member3PlantStateV1 {
    private static final String visualEpoch = java.util.UUID.randomUUID().toString();
    private static long visualSequence;
    private static long nextVisualNanos;
    public static synchronized String nextRedundancyTelemetry() {
        long now = System.nanoTime();
        if (now < nextVisualNanos) return null;
        nextVisualNanos = now + 250000000L;
        return "M3R1|" + visualEpoch + "|" + (++visualSequence) + "|" + System.currentTimeMillis() +
            "|ROTARY," + rotary.drive().visualTelemetry() +
            "|PICK," + lid.pickDrive().visualTelemetry() +
            "|PLACE," + lid.placeDrive().visualTelemetry() +
            "|POSITION," + rotary.feedback().visualTelemetry() + Member3MachineStateV1.controllerTelemetry();
    }
    // Atomic handoff avoids acquiring the Plant lock while the GUI injection lock is held.
    private static final java.util.concurrent.atomic.AtomicReference<String> pendingRedundantFault =
        new java.util.concurrent.atomic.AtomicReference<String>();
    public static boolean armRedundantFault(String code) {
        return FaultInjectionStateV2_1.isRedundantDevice(code) &&
            pendingRedundantFault.compareAndSet(null, code);
    }
    public static boolean cancelRedundantFault(String code) {
        String pending = pendingRedundantFault.get();
        return code != null && code.equals(pending) && pendingRedundantFault.compareAndSet(pending, null);
    }
    public static void clearPendingRedundantFault() { pendingRedundantFault.set(null); }

    private static void applyRedundantFault(String code, RedundantDriveV1 drive) {
        String pending = pendingRedundantFault.get();
        if (!code.equals(pending) || !pendingRedundantFault.compareAndSet(pending, null)) return;
        if (drive == null) rotary.feedback().failActive();
        else drive.fail(drive.activeChannel(), true);
        FaultInjectionStateV2_1.consumed(code);
    }
    private static void applyPhysicalFault(String axis, RedundantDriveV1 drive) {
        String code = pendingRedundantFault.get();
        if (!FaultInjectionStateV2_1.isPhysicalFault(code) || !code.startsWith(axis + "_") ||
            !pendingRedundantFault.compareAndSet(code, null)) return;
        String failure = code.substring(axis.length() + 1);
        if ("FEEDBACK_DISAGREEMENT".equals(failure)) rotary.feedback().disagree();
        else if ("LOAD_LOSS".equals(failure)) lid.loseHeldLid();
        else {
            if ("BACKUP_FAILURE".equals(failure)) drive.fail(1 - drive.activeChannel(), true);
            else drive.mechanism().inject(failure);
            drive.fail(drive.activeChannel(), true);
        }
        FaultInjectionStateV2_1.consumed(code);
    }
    private static final java.util.concurrent.atomic.AtomicBoolean rotaryDriveFailurePending =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Nonblocking controller-to-Plant request; avoids taking the Plant lock from the controller. */
    public static void queueRotaryDriveFailure() { rotaryDriveFailurePending.set(true); }
    private static final java.util.concurrent.atomic.AtomicBoolean rotaryFeedbackFailurePending =
        new java.util.concurrent.atomic.AtomicBoolean();
    public static void queueRotaryFeedbackFailure() { rotaryFeedbackFailurePending.set(true); }
    private static final java.util.Set<String> retiredBottleIds =
        new java.util.HashSet<String>();
    private static final java.util.Queue<String> pendingLoadQueue =
        new java.util.ArrayDeque<String>();
    private static final java.util.Set<String> pendingLoadIds =
        new java.util.HashSet<String>();
    private static RotaryTablePlantModelV1 rotary =
        new RotaryTablePlantModelV1();
    private static LidLoaderPlantModelV1 lid =
        new LidLoaderPlantModelV1();
    private static BoundedSignalOfferV1 fillOffer =
        new BoundedSignalOfferV1(3);
    private static BoundedSignalOfferV1 labelOffer =
        new BoundedSignalOfferV1(3);
    private static BoundedSignalOfferV1 capOffer =
        new BoundedSignalOfferV1(3);
    private static final java.util.Queue<String> twinOutbox =
        new java.util.ArrayDeque<String>();
    private static BoundedSignalOfferV1 twinOffer =
        new BoundedSignalOfferV1(3);
    private static long twinEventSequence;
    private static String pendingP6ClearId;

    private Member3PlantStateV1() {
    }

    public static synchronized void reset() {
        clearPendingRedundantFault();
        rotaryFeedbackFailurePending.set(false);
        rotaryDriveFailurePending.set(false);
        rotary = new RotaryTablePlantModelV1();
        lid = new LidLoaderPlantModelV1();
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
        twinOutbox.clear();
        twinOffer = new BoundedSignalOfferV1(3);
        twinEventSequence = 0L;
        pendingP6ClearId = null;
        pendingLoadQueue.clear();
        pendingLoadIds.clear();
        retiredBottleIds.clear();
    }

    /** Clears work in flight while preserving physical lid inventory. */
    public static synchronized void systemReset() {
        clearPendingRedundantFault();
        rotaryFeedbackFailurePending.set(false);
        rotaryDriveFailurePending.set(false);
        retiredBottleIds.addAll(rotary.activeBottleIds());
        retiredBottleIds.addAll(pendingLoadIds);
        int magazineCount = lid.getMagazineCount();
        rotary.safeStopAndClear();
        rotary = new RotaryTablePlantModelV1(rotary.drive(), rotary.feedback());
        lid.cancelAction();
        lid = new LidLoaderPlantModelV1(magazineCount, lid.pickDrive(), lid.placeDrive());
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
        twinOutbox.clear();
        twinOffer = new BoundedSignalOfferV1(3);
        pendingP6ClearId = null;
        pendingLoadQueue.clear();
        pendingLoadIds.clear();
    }

    public static synchronized boolean isResetSafe() {
        return !rotary.isMoving() && rotary.isAligned() &&
            lid.isActuatorHome() && lid.isNoLidHeld();
    }

    private static RedundantDriveV1 drive(String name) {
        if ("ROTARY".equals(name)) return rotary.drive();
        if ("PICK".equals(name)) return lid.pickDrive();
        if ("PLACE".equals(name)) return lid.placeDrive();
        throw new IllegalArgumentException("unknown M3 drive: " + name);
    }

    public static synchronized boolean injectDriveFailure(String name, int channel) {
        if (!FaultGuiActionsV2_1.isTestMode() || M3SystemResetStateV1.isQuarantined()) return false;
        RedundantDriveV1 selected = drive(name);
        selected.fail(channel, true);
        return true;
    }

    public static synchronized String driveSnapshot() {
        return rotary.drive().snapshot() + "\n\n" + lid.pickDrive().snapshot() +
            "\n\n" + lid.placeDrive().snapshot() + "\n" + rotary.physicalSnapshot() + "\n" + lid.physicalSnapshot();
    }

    public static synchronized FaultMonitoringStateV2_1.ComponentSnapshot[] driveMonitoringSnapshot() {
        return new FaultMonitoringStateV2_1.ComponentSnapshot[] {
            rotary.drive().monitoringSnapshot(), lid.pickDrive().monitoringSnapshot(),
            lid.placeDrive().monitoringSnapshot(), rotary.feedback().monitoringSnapshot()
        };
    }

    public static synchronized boolean loadBottle(String id) {
        if (M3SystemResetStateV1.isQuarantined() ||
            FaultSupervisorStateV2_1.isOperationHeld() ||
            retiredBottleIds.contains(id)) {
            return false;
        }
        return rotary.loadBottle(id);
    }

    /**
     * Accepts the one-reaction M2 load event without dropping it while P1 is
     * occupied or M3 is fault-held. Repeated transport copies are idempotent.
     */
    public static synchronized boolean acceptLoadRequest(String id) {
        if (M3SystemResetStateV1.isQuarantined() || !validBottleId(id)) {
            return false;
        }
        if (retiredBottleIds.contains(id)) {
            return true;
        }
        if (rotary.hasActiveBottle(id) || pendingLoadIds.contains(id)) {
            FaultSupervisorStateV2_1.onRecoveredTransferHandoff(id);
            return true;
        }
        boolean recoveryHandoff = isRecoveryHandoff(id);
        if ((!FaultSupervisorStateV2_1.isOperationHeld() || recoveryHandoff) &&
            rotary.loadBottle(id)) {
            if (recoveryHandoff) {
                FaultSupervisorStateV2_1.onRecoveredTransferHandoff(id);
            }
            return true;
        }
        pendingLoadQueue.add(id);
        pendingLoadIds.add(id);
        if (recoveryHandoff) {
            FaultSupervisorStateV2_1.onRecoveredTransferHandoff(id);
        }
        return true;
    }

    /** Loads at most one queued bottle when the physical P1 slot is free. */
    public static synchronized boolean drainPendingLoad() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        if (FaultSupervisorStateV2_1.isOperationHeld()) {
            return drainRecoveryHandoff();
        }
        while (!pendingLoadQueue.isEmpty()) {
            String id = pendingLoadQueue.peek();
            if (retiredBottleIds.contains(id) || rotary.hasActiveBottle(id)) {
                pendingLoadQueue.remove();
                pendingLoadIds.remove(id);
                continue;
            }
            if (!rotary.loadBottle(id)) {
                return false;
            }
            pendingLoadQueue.remove();
            pendingLoadIds.remove(id);
            return true;
        }
        return false;
    }

    private static boolean drainRecoveryHandoff() {
        java.util.Iterator<String> iterator = pendingLoadQueue.iterator();
        while (iterator.hasNext()) {
            String id = iterator.next();
            if (!isRecoveryHandoff(id)) {
                continue;
            }
            if (rotary.hasActiveBottle(id)) {
                iterator.remove();
                pendingLoadIds.remove(id);
                return true;
            }
            if (!rotary.loadBottle(id)) {
                return false;
            }
            iterator.remove();
            pendingLoadIds.remove(id);
            FaultSupervisorStateV2_1.onRecoveredTransferHandoff(id);
            return true;
        }
        return false;
    }

    private static boolean isRecoveryHandoff(String id) {
        String state = FaultSupervisorStateV2_1.stateName();
        return ("WAITING_RESULT".equals(state) ||
            "LOCKED_OUT".equals(state)) &&
            "TRANSFER".equals(FaultSupervisorStateV2_1.activeSubsystem()) &&
            "ARRIVAL_TIMEOUT".equals(
                FaultSupervisorStateV2_1.activeFaultCode()) &&
            id != null && id.equals(FaultSupervisorStateV2_1.activeBottleId());
    }

    static synchronized int pendingLoadCount() {
        return pendingLoadQueue.size();
    }

    private static boolean validBottleId(String id) {
        try {
            BottleContextV1.validateBottleId(id);
            return true;
        }
        catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static synchronized boolean registerBottleContext(String payload) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        String bottleId = payload == null ? null : payload.split("\\|", -1)[0];
        if (retiredBottleIds.contains(bottleId)) {
            return true;
        }
        return rotary.registerContext(payload);
    }

    public static synchronized void setRotaryMotor(
        boolean enabled,
        long cycleId
    ) {
        if (M3SystemResetStateV1.isQuarantined() ||
            FaultSupervisorStateV2_1.isOperationHeld()) {
            enabled = false;
            cycleId = 0L;
        }
        boolean started = rotary.setMotorCommand(enabled, cycleId,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        if (started && rotaryDriveFailurePending.getAndSet(false)) {
            rotary.drive().fail(rotary.drive().activeChannel(), true);
        }
        if (started && rotaryFeedbackFailurePending.getAndSet(false)) {
            rotary.feedback().failActive();
        }
    }

    public static synchronized boolean updateRotary() {
        return updateRotaryAt(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized boolean updateRotaryAt(long nowMs) {
        if (SystemWatchdogV1.isSafeError()) return false;
        if (rotary.isMoving()) {
            applyPhysicalFault("ROTARY", rotary.drive());
            applyRedundantFault("ROTARY_DRIVE_FAILURE", rotary.drive());
            applyRedundantFault("ROTARY_FEEDBACK_FAILURE", null);
        }
        boolean aligned = rotary.tick(nowMs);
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.ROTARY_PLANT,
            true,
            aligned ? "ALIGNED" : "MOVING_OR_WAITING"
        );
        return aligned;
    }

    public static synchronized boolean commitRotation(long cycleId) {
        if (M3SystemResetStateV1.isQuarantined() ||
            FaultSupervisorStateV2_1.isOperationHeld()) {
            return false;
        }
        return rotary.commitRotation(cycleId);
    }

    public static synchronized boolean markFilled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = retiredBottleIds.contains(bottleId) ||
            rotary.markFilled(bottleId);
        if (accepted) {
            fillOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLidPlaced(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        if (retiredBottleIds.contains(bottleId)) {
            return true;
        }
        boolean duplicate = rotary.hasRecordedLidPlacement(bottleId);
        boolean accepted = rotary.markLidPlaced(bottleId);
        if (accepted && !duplicate) {
            twinOutbox.add("V1|W|M3-LID-" + (++twinEventSequence) +
                "|" + bottleId + "|LIDDED|LID-1|-|" +
                System.currentTimeMillis());
        }
        return accepted;
    }

    public static synchronized boolean markCapped(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = retiredBottleIds.contains(bottleId) ||
            rotary.markCapped(bottleId);
        if (accepted) {
            capOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLabelled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = retiredBottleIds.contains(bottleId) ||
            rotary.markLabelled(bottleId);
        if (accepted) {
            labelOffer.acknowledge(bottleId);
            if (bottleId.equals(pendingP6ClearId) &&
                rotary.clearP6(bottleId)) {
                pendingP6ClearId = null;
            }
        }
        return accepted;
    }

    public static synchronized boolean clearP6(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        if (retiredBottleIds.contains(bottleId) ||
            rotary.clearP6(bottleId)) {
            pendingP6ClearId = null;
            return true;
        }
        String atP6 = rotary.positionLabel(5);
        if (atP6.startsWith(bottleId + "[") &&
            !atP6.equals(bottleId + "[FLCB]")) {
            pendingP6ClearId = bottleId;
            return true;
        }
        return false;
    }

    public static synchronized void setAlignmentFault(boolean active) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return;
        }
        rotary.setAlignmentFault(active);
    }

    public static synchronized boolean isTableAligned() {
        return rotary.isAligned();
    }

    public static synchronized boolean canRotate() {
        return !M3SystemResetStateV1.isQuarantined() &&
            !FaultSupervisorStateV2_1.isOperationHeld() &&
            rotary.canRotate();
    }

    public static synchronized String getBottleWaitingForLidId() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        return rotary.getBottleWaitingForLidId();
    }

    public static synchronized String takeFillOffer() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        return rotary.takeFillOffer();
    }

    public static synchronized String nextFillOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        if (!fillOffer.isActive()) {
            String payload = rotary.takeFillOffer();
            if (payload != null) {
                fillOffer.arm(payload.split("\\|", -1)[0], payload);
            }
        }
        return fillOffer.nextReactionValue();
    }

    public static synchronized String takeCapOffer() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        return rotary.takeCapOffer();
    }

    public static synchronized String nextCapOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        if (!capOffer.isActive()) {
            String payload = rotary.takeCapOffer();
            if (payload != null) {
                capOffer.arm(payload.split("\\|", -1)[0], payload);
            }
        }
        return capOffer.nextReactionValue();
    }

    public static synchronized String takeLabelOffer() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        return rotary.takeLabelOffer();
    }

    public static synchronized String nextLabelOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        if (!labelOffer.isActive()) {
            String payload = rotary.takeLabelOffer();
            if (payload != null) {
                labelOffer.arm(payload, payload);
            }
        }
        return labelOffer.nextReactionValue();
    }

    public static synchronized String rotarySnapshot() {
        return rotary.snapshot();
    }

    public static synchronized M3PickRecoveryV1.PlantEvidence pickRecoveryEvidence(String bottle) {
        boolean matching = bottle != null && bottle.equals(rotary.getBottleWaitingForLidId());
        boolean drives = ("AVAILABLE".equals(lid.pickDrive().monitoringState()) ||
            "DEGRADED".equals(lid.pickDrive().monitoringState())) &&
            ("AVAILABLE".equals(lid.placeDrive().monitoringState()) ||
            "DEGRADED".equals(lid.placeDrive().monitoringState()));
        return new M3PickRecoveryV1.PlantEvidence(matching && !M3SystemResetStateV1.isQuarantined(),
            lid.isActuatorHome(), lid.isNoLidHeld(), lid.isLidAvailable(), drives,
            lid.completedPlacements());
    }

    public static synchronized String positionLabel(int position) {
        return rotary.positionLabel(position);
    }

    public static synchronized void setPickCommand(boolean enabled) {
        if (M3SystemResetStateV1.isQuarantined()) {
            enabled = false;
        }
        lid.setPickCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized void setPlaceCommand(boolean enabled) {
        if (M3SystemResetStateV1.isQuarantined()) {
            enabled = false;
        }
        lid.setPlaceCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized void updateLidLoader() {
        updateLidLoaderAt(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized void updateLidLoaderAt(long nowMs) {
        if (SystemWatchdogV1.isSafeError()) return;
        if (!FaultSupervisorStateV2_1.permitsLocalPickMotion()) {
            lid.pauseMotion(nowMs);
            return;
        }
        if ("PICKING".equals(lid.getActionName())) applyPhysicalFault("PICK", lid.pickDrive());
        if ("PLACING".equals(lid.getActionName())) applyPhysicalFault("PLACE", lid.placeDrive());
        if ("PICKING".equals(lid.getActionName())) applyRedundantFault("PICK_DRIVE_FAILURE", lid.pickDrive());
        if ("PLACING".equals(lid.getActionName())) applyRedundantFault("PLACE_DRIVE_FAILURE", lid.placeDrive());
        lid.tick(nowMs);
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.LID_PLANT,
            true,
            lid.getActionName()
        );
    }

    public static synchronized boolean isLidAvailable() {
        return lid.isLidAvailable();
    }

    public static synchronized boolean isLidPicked() {
        return lid.isLidPicked();
    }

    public static synchronized boolean isLidPlacedSensorActive() {
        return lid.isLidPlacedSensorActive(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized int getLidMagazineCount() {
        return lid.getMagazineCount();
    }

    public static synchronized int getLidMagazineCapacity() {
        return lid.getMagazineCapacity();
    }

    public static synchronized String getLidActionName() {
        return lid.getActionName();
    }

    public static synchronized boolean isLidActuatorHome() {
        return lid.isActuatorHome();
    }

    public static synchronized boolean isNoLidHeld() {
        return lid.isNoLidHeld();
    }

    public static synchronized boolean isLidPlacementSensorHealthy() {
        return lid.isPlacementSensorHealthy();
    }

    public static synchronized int refillLids(int count) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return lid.getMagazineCount();
        }
        return lid.refill(count);
    }

    public static synchronized void setPickFault(boolean active) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return;
        }
        lid.setPickFault(active);
    }

    public static synchronized void setPlaceFault(boolean active) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return;
        }
        lid.setPlaceFault(active);
    }

    public static synchronized void cancelLidAction() {
        lid.cancelAction();
    }

    public static synchronized String nextTwinObservation() {
        if (M3SystemResetStateV1.isQuarantined()) {
            return null;
        }
        if (!twinOffer.isActive() && !twinOutbox.isEmpty()) {
            String payload = twinOutbox.remove();
            twinOffer.arm(payload.split("\\|", -1)[2], payload);
        }
        return twinOffer.nextReactionValue();
    }
}
