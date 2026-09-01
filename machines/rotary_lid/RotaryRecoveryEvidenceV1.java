/** Independent evidence required before an alignment fault can be cleared. */
public final class RotaryRecoveryEvidenceV1 {
    private final boolean m1SafeStopAcknowledged;
    private final boolean bottlePositionsReconciled;
    private final boolean independentPositionConfirmed;

    public RotaryRecoveryEvidenceV1(
        boolean m1SafeStopAcknowledged,
        boolean bottlePositionsReconciled,
        boolean independentPositionConfirmed
    ) {
        this.m1SafeStopAcknowledged = m1SafeStopAcknowledged;
        this.bottlePositionsReconciled = bottlePositionsReconciled;
        this.independentPositionConfirmed = independentPositionConfirmed;
    }

    public boolean permitsReset() {
        return m1SafeStopAcknowledged && bottlePositionsReconciled &&
            independentPositionConfirmed;
    }
}
