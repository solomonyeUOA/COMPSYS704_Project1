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

    private Member3PlantStateV1() {
    }

    public static synchronized void reset() {
        rotary = new RotaryTablePlantModelV1();
        lid = new LidLoaderPlantModelV1();
        fillOffer = new BoundedSignalOfferV1(3);
        labelOffer = new BoundedSignalOfferV1(3);
        capOffer = new BoundedSignalOfferV1(3);
    }

    public static synchronized boolean loadBottle(String id) {
        return rotary.loadBottle(id);
    }

    public static synchronized boolean registerBottleContext(String payload) {
        return rotary.registerContext(payload);
    }

    public static synchronized void setRotaryMotor(
        boolean enabled,
        long cycleId
    ) {
        rotary.setMotorCommand(enabled, cycleId, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized boolean updateRotary() {
        return rotary.tick(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized boolean commitRotation(long cycleId) {
        return rotary.commitRotation(cycleId);
    }

    public static synchronized boolean markFilled(String bottleId) {
        boolean accepted = rotary.markFilled(bottleId);
        if (accepted) {
            fillOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLidPlaced(String bottleId) {
        return rotary.markLidPlaced(bottleId);
    }

    public static synchronized boolean markCapped(String bottleId) {
        boolean accepted = rotary.markCapped(bottleId);
        if (accepted) {
            capOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean markLabelled(String bottleId) {
        boolean accepted = rotary.markLabelled(bottleId);
        if (accepted) {
            labelOffer.acknowledge(bottleId);
        }
        return accepted;
    }

    public static synchronized boolean clearP6(String bottleId) {
        return rotary.clearP6(bottleId);
    }

    public static synchronized void setAlignmentFault(boolean active) {
        rotary.setAlignmentFault(active);
    }

    public static synchronized boolean isTableAligned() {
        return rotary.isAligned();
    }

    public static synchronized boolean canRotate() {
        return rotary.canRotate();
    }

    public static synchronized String getBottleWaitingForLidId() {
        return rotary.getBottleWaitingForLidId();
    }

    public static synchronized String takeFillOffer() {
        return rotary.takeFillOffer();
    }

    public static synchronized String nextFillOfferWindow() {
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
        lid.setPickCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized void setPlaceCommand(boolean enabled) {
        lid.setPlaceCommand(enabled, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public static synchronized void updateLidLoader() {
        lid.tick(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
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

    public static synchronized void refillLids(int count) {
        lid.refill(count);
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
}
