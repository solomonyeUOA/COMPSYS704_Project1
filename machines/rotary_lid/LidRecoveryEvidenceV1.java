/** Cause-specific evidence used to clear a Lid Loader fault. */
public final class LidRecoveryEvidenceV1 {
    private final boolean lidAvailable;
    private final boolean actuatorHome;
    private final boolean noLidHeld;
    private final boolean placementReconciled;
    private final boolean placementSensorHealthy;

    public LidRecoveryEvidenceV1(
        boolean lidAvailable,
        boolean actuatorHome,
        boolean noLidHeld,
        boolean placementReconciled,
        boolean placementSensorHealthy
    ) {
        this.lidAvailable = lidAvailable;
        this.actuatorHome = actuatorHome;
        this.noLidHeld = noLidHeld;
        this.placementReconciled = placementReconciled;
        this.placementSensorHealthy = placementSensorHealthy;
    }

    public boolean permitsReset(LidLoaderControllerModelV1.Fault fault) {
        if (fault == LidLoaderControllerModelV1.Fault.MAGAZINE_EMPTY) {
            return lidAvailable;
        }
        if (fault == LidLoaderControllerModelV1.Fault.PICK_TIMEOUT) {
            return lidAvailable && actuatorHome && noLidHeld;
        }
        if (fault == LidLoaderControllerModelV1.Fault.PLACEMENT_TIMEOUT ||
            fault == LidLoaderControllerModelV1.Fault.LID_SENSOR_FAULT) {
            return actuatorHome && placementReconciled &&
                placementSensorHealthy;
        }
        return false;
    }
}
