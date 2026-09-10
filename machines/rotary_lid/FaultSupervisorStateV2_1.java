/** Shared state facade used by FaultSupervisorCD and the read-only GUI. */
public final class FaultSupervisorStateV2_1 {
    private static final FaultSupervisorModelV2_1 MODEL =
        new FaultSupervisorModelV2_1();

    private FaultSupervisorStateV2_1() {
    }

    public static boolean onTransferFault(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_FAULT_EVENT"
        );
        return MODEL.onTransferFault(payload);
    }

    public static boolean onFaultEvent(String payload) {
        return MODEL.onFaultEvent(payload);
    }

    public static boolean onRecoveryAck(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_RECOVERY_ACK"
        );
        return MODEL.onRecoveryAck(payload);
    }

    public static boolean onRecoveryResult(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_RECOVERY_RESULT"
        );
        return MODEL.onRecoveryResult(payload);
    }

    public static boolean onSafeStopAck(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M1_LINK,
            "FT_SAFE_STOP_ACK"
        );
        return MODEL.onSafeStopAck(payload);
    }

    public static boolean onResumeDecision(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M1_LINK,
            "FT_RESUME_DECISION"
        );
        return MODEL.onResumeDecision(payload);
    }

    public static String takeRecoveryRequest() {
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.SUPERVISOR,
            !"FAILED".equals(MODEL.getState().name()),
            MODEL.getState().name()
        );
        MODEL.tick(System.currentTimeMillis());
        return MODEL.takeRecoveryRequest();
    }

    public static String takeFaultAlert() {
        return MODEL.takeFaultAlert();
    }

    public static String takeSafeStopRequest() {
        return MODEL.takeSafeStopRequest();
    }

    public static String takeRecoveryReady() {
        return MODEL.takeRecoveryReady();
    }

    public static String takeRecoveryFailed() {
        return MODEL.takeRecoveryFailed();
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

    public static String activeEpoch() {
        return MODEL.getActiveEpoch();
    }

    public static String activeSubsystem() {
        return MODEL.getActiveSubsystem();
    }

    public static String activeFaultCode() {
        return MODEL.getActiveFaultCode();
    }

    public static String activeSeverity() {
        return MODEL.getActiveSeverity();
    }

    public static String activeBottleId() {
        return MODEL.getActiveBottleId();
    }

    public static long activeStateVersion() {
        return MODEL.getActiveStateVersion();
    }

    public static long latestStateVersion() {
        return MODEL.getLatestStateVersion();
    }

    public static int activeAttempt() {
        return MODEL.getActiveAttempt();
    }

    public static int maximumAttempts() {
        return MODEL.getMaximumAttempts();
    }

    public static long stateEnteredAtMs() {
        return MODEL.getStateEnteredAtMs();
    }

    public static String policySummary() {
        return MODEL.getPolicySummary();
    }

    public static String requiredSafeEvidence() {
        return MODEL.getRequiredSafeEvidence();
    }

    public static String requiredServiceEvidence() {
        return MODEL.getRequiredServiceEvidence();
    }

    public static String latestEvidence() {
        return MODEL.getLatestEvidence();
    }

    public static String localSummary() {
        return MODEL.getLocalSummary();
    }

    public static String[] historySnapshot() {
        return MODEL.historySnapshot();
    }

    public static FaultSupervisorMetricsV2_1 metricsSnapshot() {
        return MODEL.metricsSnapshot();
    }

    public static boolean recordManualEvidence(
        ManualReconciliationEvidenceV2_1 evidence
    ) {
        return MODEL.recordManualEvidence(evidence);
    }

    public static boolean confirmManualControllerEvidence(
        String eventId,
        String sourceEpoch,
        String safeEvidence,
        String serviceEvidence,
        long resultingStateVersion
    ) {
        return MODEL.confirmManualControllerEvidence(
            eventId,
            sourceEpoch,
            safeEvidence,
            serviceEvidence,
            resultingStateVersion
        );
    }

    public static boolean confirmResourceRestored(
        String eventId,
        boolean lidAvailable,
        long resultingStateVersion
    ) {
        return MODEL.confirmResourceRestored(
            eventId,
            lidAvailable,
            resultingStateVersion
        );
    }

    public static void reset() {
        MODEL.reset();
    }

    public static void systemReset() {
        MODEL.systemReset();
    }

    static FaultSupervisorModelV2_1 modelForTest() {
        return MODEL;
    }
}
