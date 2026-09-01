/** Shared state facade used by FaultSupervisorCD and the read-only GUI. */
public final class FaultSupervisorStateV2_1 {
    private static final FaultSupervisorModelV2_1 MODEL =
        new FaultSupervisorModelV2_1();

    private FaultSupervisorStateV2_1() {
    }

    public static boolean onTransferFault(String payload) {
        return MODEL.onTransferFault(payload);
    }

    public static boolean onRecoveryAck(String payload) {
        return MODEL.onRecoveryAck(payload);
    }

    public static boolean onRecoveryResult(String payload) {
        return MODEL.onRecoveryResult(payload);
    }

    public static String takeRecoveryRequest() {
        return MODEL.takeRecoveryRequest();
    }

    public static void observeRotaryFault(String eventId, String reason) {
        MODEL.observeRotaryFault(eventId, reason);
    }

    public static void observeLidFault(
        String eventId,
        LidLoaderControllerModelV1.Fault fault
    ) {
        MODEL.observeLidFault(eventId, fault);
    }

    public static boolean authorizeRotaryReset(
        String eventId,
        RotaryRecoveryEvidenceV1 evidence
    ) {
        return MODEL.authorizeRotaryReset(eventId, evidence);
    }

    public static boolean authorizeLidReset(
        String eventId,
        LidLoaderControllerModelV1.Fault fault,
        LidRecoveryEvidenceV1 evidence
    ) {
        return MODEL.authorizeLidReset(eventId, fault, evidence);
    }

    public static void resolveLocalFault(String subsystem, String eventId) {
        MODEL.resolveLocalFault(subsystem, eventId);
    }

    public static String stateName() {
        return MODEL.getState().name();
    }

    public static String decision() {
        return MODEL.getDecision();
    }

    public static String activeEventId() {
        return MODEL.getActiveEventId();
    }

    public static String localSummary() {
        return MODEL.getLocalSummary();
    }

    public static String[] historySnapshot() {
        return MODEL.historySnapshot();
    }

    public static void reset() {
        MODEL.reset();
    }

    static FaultSupervisorModelV2_1 modelForTest() {
        return MODEL;
    }
}
