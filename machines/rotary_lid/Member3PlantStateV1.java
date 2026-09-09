/** Shared Plant state used by the Member 3 SystemJ Plant clock-domains. */
public final class Member3PlantStateV1 {
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
    private static BoundedSignalOfferV1 twinOffer = new BoundedSignalOfferV1(3);
    private static long twinEventSequence;

    private Member3PlantStateV1() {
    }

    public static synchronized void reset() {
        rotary = new RotaryTablePlantModelV1();
        lid = new LidLoaderPlantModelV1();
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
        twinOutbox.clear();
        twinOffer = new BoundedSignalOfferV1(3);
        twinEventSequence = 0;
    }

    public static synchronized boolean loadBottle(String id) {
        if (M3SystemResetStateV1.isQuarantined()) {
            rotary.retireBottle(id);
            return false;
        }
        return rotary.loadBottle(id);
    }

    public static synchronized boolean registerBottleContext(String payload) {
        if (M3SystemResetStateV1.isQuarantined()) {
            rotary.retireContext(payload);
            return false;
        }
        return rotary.registerContext(payload);
    }

    public static synchronized void setRotaryMotor(
        boolean enabled,
        long cycleId
    ) {
        setRotaryMotor(enabled, cycleId, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized void setRotaryMotor(boolean enabled, long cycleId, long nowMs) {
        if (M3SystemResetStateV1.isQuarantined()) return;
        rotary.setMotorCommand(enabled, cycleId, nowMs);
    }

    public static synchronized boolean updateRotary() {
        return updateRotary(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized boolean updateRotary(long nowMs) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        return rotary.tick(nowMs);
    }

    public static synchronized boolean commitRotation(long cycleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        return rotary.commitRotation(cycleId);
    }

    public static synchronized boolean markFilled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        boolean accepted = rotary.markFilled(bottleId);
        if (accepted) {
            fillOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLidPlaced(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        boolean accepted = rotary.markLidPlaced(bottleId);
        if (accepted) twinOutbox.add("V1|W|M3-LID-" + (++twinEventSequence) +
            "|" + bottleId + "|LIDDED|LID-1|-|" + System.currentTimeMillis());
        return accepted;
    }

    public static synchronized boolean markCapped(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        boolean accepted = rotary.markCapped(bottleId);
        if (accepted) {
            capOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLabelled(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        boolean accepted = rotary.markLabelled(bottleId);
        if (accepted) {
            labelOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean clearP6(String bottleId) {
        if (M3SystemResetStateV1.isQuarantined()) return false;
        return rotary.clearP6(bottleId);
    }

    public static synchronized void setAlignmentFault(boolean active) {
        rotary.setAlignmentFault(active);
    }

    public static synchronized boolean isTableAligned() {
        return rotary.isAligned();
    }

    public static synchronized boolean canRotate() {
        return !M3SystemResetStateV1.isQuarantined() && rotary.canRotate();
    }

    public static synchronized String getBottleWaitingForLidId() {
        if (M3SystemResetStateV1.isQuarantined()) return null;
        return rotary.getBottleWaitingForLidId();
    }

    public static synchronized String takeFillOffer() {
        return rotary.takeFillOffer();
    }

    public static synchronized String nextFillOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) return null;
        if (!fillOffer.isActive()) {
            String payload = rotary.takeFillOffer();
            if (payload != null) {
                fillOffer.arm(payload.split("\\|", -1)[0], payload);
            }
        }
        return fillOffer.nextReactionValue();
    }

    public static synchronized String takeCapOffer() {
        return rotary.takeCapOffer();
    }

    public static synchronized String nextCapOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) return null;
        if (!capOffer.isActive()) {
            String payload = rotary.takeCapOffer();
            if (payload != null) {
                capOffer.arm(payload.split("\\|", -1)[0], payload);
            }
        }
        return capOffer.nextReactionValue();
    }

    public static synchronized String takeLabelOffer() {
        return rotary.takeLabelOffer();
    }

    public static synchronized String nextLabelOfferWindow() {
        if (M3SystemResetStateV1.isQuarantined()) return null;
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
        setPickCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized void setPickCommand(boolean enabled, long nowMs) {
        if (M3SystemResetStateV1.isQuarantined()) return;
        lid.setPickCommand(enabled, nowMs);
    }

    public static synchronized void setPlaceCommand(boolean enabled) {
        setPlaceCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized void setPlaceCommand(boolean enabled, long nowMs) {
        if (M3SystemResetStateV1.isQuarantined()) return;
        lid.setPlaceCommand(enabled, nowMs);
    }

    public static synchronized void updateLidLoader() {
        updateLidLoader(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    static synchronized void updateLidLoader(long nowMs) {
        if (M3SystemResetStateV1.isQuarantined()) return;
        lid.tick(nowMs);
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
        return lid.refill(count);
    }

    public static synchronized void setPickFault(boolean active) {
        lid.setPickFault(active);
    }

    public static synchronized void setPlaceFault(boolean active) {
        lid.setPlaceFault(active);
    }

    public static synchronized void cancelLidAction() {
        lid.cancelAction();
    }

    public static synchronized void stopForSystemReset() {
        rotary.stopForSystemReset();
        lid.resetRuntime();
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
        twinOutbox.clear();
        twinOffer = new BoundedSignalOfferV1(3);
    }

    public static synchronized boolean isSystemResetSafe() {
        return !rotary.isMoving() && lid.isActuatorHome() && lid.isNoLidHeld();
    }

    public static synchronized String reconcileSimulatedReset(String resetId) {
        if (!isSystemResetSafe()) return null;
        return rotary.reconcileSimulatedReset(resetId);
    }

    public static synchronized String nextTwinObservation() {
        if (M3SystemResetStateV1.isQuarantined()) return null;
        if (!twinOffer.isActive() && !twinOutbox.isEmpty()) {
            String payload = twinOutbox.remove();
            twinOffer.arm(payload.split("\\|", -1)[2], payload);
        }
        return twinOffer.nextReactionValue();
    }
}
