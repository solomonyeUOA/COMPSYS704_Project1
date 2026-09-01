/** Shared Plant state used by the Member 3 SystemJ Plant clock-domains. */
public final class Member3PlantStateV1 {
    private static RotaryTablePlantModelV1 rotary =
        new RotaryTablePlantModelV1();
    private static LidLoaderPlantModelV1 lid =
        new LidLoaderPlantModelV1();

    private Member3PlantStateV1() {
    }

    public static synchronized void reset() {
        rotary = new RotaryTablePlantModelV1();
        lid = new LidLoaderPlantModelV1();
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
        rotary.setMotorCommand(enabled, cycleId, System.currentTimeMillis());
    }

    public static synchronized boolean updateRotary() {
        return rotary.tick(System.currentTimeMillis());
    }

    public static synchronized boolean commitRotation(long cycleId) {
        return rotary.commitRotation(cycleId);
    }

    public static synchronized boolean markFilled(String bottleId) {
        return rotary.markFilled(bottleId);
    }

    public static synchronized boolean markLidPlaced(String bottleId) {
        return rotary.markLidPlaced(bottleId);
    }

    public static synchronized boolean markCapped(String bottleId) {
        return rotary.markCapped(bottleId);
    }

    public static synchronized boolean markLabelled(String bottleId) {
        return rotary.markLabelled(bottleId);
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

    public static synchronized String takeCapOffer() {
        return rotary.takeCapOffer();
    }

    public static synchronized String takeLabelOffer() {
        return rotary.takeLabelOffer();
    }

    public static synchronized String rotarySnapshot() {
        return rotary.snapshot();
    }

    public static synchronized String positionLabel(int position) {
        return rotary.positionLabel(position);
    }

    public static synchronized void setPickCommand(boolean enabled) {
        lid.setPickCommand(enabled, System.currentTimeMillis());
    }

    public static synchronized void setPlaceCommand(boolean enabled) {
        lid.setPlaceCommand(enabled, System.currentTimeMillis());
    }

    public static synchronized void updateLidLoader() {
        lid.tick(System.currentTimeMillis());
    }

    public static synchronized boolean isLidAvailable() {
        return lid.isLidAvailable();
    }

    public static synchronized boolean isLidPicked() {
        return lid.isLidPicked();
    }

    public static synchronized boolean isLidPlacedSensorActive() {
        return lid.isLidPlacedSensorActive(System.currentTimeMillis());
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
