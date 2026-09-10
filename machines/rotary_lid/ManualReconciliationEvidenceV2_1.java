/** Auditable operator evidence. It cannot clear a fault by itself. */
public final class ManualReconciliationEvidenceV2_1 {
    public final String eventId;
    public final String sourceEpoch;
    public final String subsystem;
    public final String bottleId;
    public final long stateVersion;
    public final String operatorId;
    public final String evidenceCode;

    public ManualReconciliationEvidenceV2_1(
        String eventId,
        String sourceEpoch,
        String subsystem,
        String bottleId,
        long stateVersion,
        String operatorId,
        String evidenceCode
    ) {
        this.eventId = token(eventId, "eventId");
        this.sourceEpoch = token(sourceEpoch, "sourceEpoch");
        this.subsystem = token(subsystem, "subsystem");
        this.bottleId = token(bottleId, "bottleId");
        this.operatorId = token(operatorId, "operatorId");
        this.evidenceCode = token(evidenceCode, "evidenceCode");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be unsigned");
        }
        this.stateVersion = stateVersion;
    }

    private static String token(String value, String name) {
        if (value == null || value.isEmpty() ||
            !value.equals(value.trim()) || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
