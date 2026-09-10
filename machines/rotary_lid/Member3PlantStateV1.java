/** Shared Plant state used by the Member 3 SystemJ Plant clock-domains. */
public final class Member3PlantStateV1 {
    private static final java.util.Set<String> retiredBottleIds =
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

    private Member3PlantStateV1() {
    }

    public static synchronized void reset() {
        rotary = new RotaryTablePlantModelV1();
        lid = new LidLoaderPlantModelV1();
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
        retiredBottleIds.clear();
    }

    /** Clears work in flight while preserving physical lid inventory. */
    public static synchronized void systemReset() {
        retiredBottleIds.addAll(rotary.activeBottleIds());
        int magazineCount = lid.getMagazineCount();
        rotary.safeStopAndClear();
        rotary = new RotaryTablePlantModelV1();
        lid.cancelAction();
        lid = new LidLoaderPlantModelV1(magazineCount);
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
    }

    public static synchronized boolean isResetSafe() {
        return !rotary.isMoving() && rotary.isAligned() &&
            lid.isActuatorHome() && lid.isNoLidHeld();
    }

    public static synchronized boolean loadBottle(String id) {
        if (M3SystemResetStateV1.isQuarantined() ||
            retiredBottleIds.contains(id)) {
            return false;
        }
        return rotary.loadBottle(id);
    }

    public static synchronized boolean registerBottleContext(String payload) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        String bottleId = payload == null ? null : payload.split("\\|", -1)[0];
        if (retiredBottleIds.contains(bottleId)) {
            return false;
        }
        return rotary.registerContext(payload);
    }

    public static synchronized void setRotaryMotor(
        boolean enabled,
        long cycleId
    ) {
        if (M3SystemResetStateV1.isQuarantined()) {
            enabled = false;
            cycleId = 0L;
        }
        rotary.setMotorCommand(enabled, cycleId, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized boolean updateRotary() {
        boolean aligned = rotary.tick(
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.ROTARY_PLANT,
            true,
            aligned ? "ALIGNED" : "MOVING_OR_WAITING"
        );
        return aligned;
    }

    public static synchronized boolean commitRotation(long cycleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        return rotary.commitRotation(cycleId);
    }

    public static synchronized boolean markFilled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = rotary.markFilled(bottleId);
        if (accepted) {
            fillOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLidPlaced(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        return rotary.markLidPlaced(bottleId);
    }

    public static synchronized boolean markCapped(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = rotary.markCapped(bottleId);
        if (accepted) {
            capOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLabelled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        boolean accepted = rotary.markLabelled(bottleId);
        if (accepted) {
            labelOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean clearP6(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) {
            return false;
        }
        return rotary.clearP6(bottleId);
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
        return !M3SystemResetStateV1.isQuarantined() && rotary.canRotate();
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
        lid.tick(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
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
}
