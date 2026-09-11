/** Shared state facade used by FaultSupervisorCD and the read-only GUI. */
public final class FaultSupervisorStateV2_1 {
    private static final FaultSupervisorModelV2_1 MODEL =
        new FaultSupervisorModelV2_1();
    private static final BoundedStringSignalOfferV1 RECOVERY_REQUEST_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static final BoundedStringSignalOfferV1 FAULT_ALERT_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static final BoundedStringSignalOfferV1 SAFE_STOP_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static final BoundedStringSignalOfferV1 RECOVERY_READY_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static final BoundedStringSignalOfferV1 RECOVERY_FAILED_OFFER =
        new BoundedStringSignalOfferV1(3, 500L, 100L);
    private static long localStateVersion;
    private static long localEpochGeneration;

    private FaultSupervisorStateV2_1() {
    }

    public static boolean onTransferFault(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_FAULT_EVENT"
        );
        boolean accepted = MODEL.onTransferFault(payload);
        if (accepted) {
            try {
                FaultInjectionStateV2_1.consumed(
                    FaultProtocolV2_1.parseFaultEvent(payload).faultCode
                );
            }
            catch (IllegalArgumentException ignored) {
            }
        }
        return accepted;
    }

    public static boolean onFaultEvent(String payload) {
        return MODEL.onFaultEvent(payload);
    }

    public static boolean onRecoveryAck(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_RECOVERY_ACK"
        );
        boolean accepted = MODEL.onRecoveryAck(payload);
        if (accepted) {
            RECOVERY_REQUEST_OFFER.discard();
        }
        return accepted;
    }

    public static boolean onRecoveryResult(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M2_LINK,
            "TRANSFER_RECOVERY_RESULT"
        );
        if ("LOCKED_OUT".equals(MODEL.getState().name())) {
            try {
                FaultProtocolV2_1.RecoveryResult result =
                    FaultProtocolV2_1.parseRecoveryResult(payload);
                boolean accepted = MODEL.confirmManualControllerEvidence(
                    result.eventId,
                    result.sourceEpoch,
                    result.safeEvidence,
                    result.serviceEvidence,
                    result.resultingStateVersion
                );
                if (accepted) {
                    FaultTestControlStateV2_1.acknowledgeTransfer();
                }
                return accepted;
            }
            catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return MODEL.onRecoveryResult(payload);
    }

    public static boolean onSafeStopAck(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M1_LINK,
            "FT_SAFE_STOP_ACK"
        );
        boolean accepted = MODEL.onSafeStopAck(payload);
        if (accepted) {
            SAFE_STOP_OFFER.discard();
            FaultTestControlStateV2_1.acknowledge();
        }
        return accepted;
    }

    public static boolean onResumeDecision(String payload) {
        FaultMonitoringStateV2_1.peerTraffic(
            FaultMonitoringStateV2_1.M1_LINK,
            "FT_RESUME_DECISION"
        );
        boolean accepted = MODEL.onResumeDecision(payload);
        if (accepted) {
            RECOVERY_READY_OFFER.discard();
            FaultTestControlStateV2_1.acknowledge();
        }
        return accepted;
    }

    public static String takeRecoveryRequest() {
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.SUPERVISOR,
            !"FAILED".equals(MODEL.getState().name()),
            MODEL.getState().name()
        );
        if (!FaultGuiActionsV2_1.isTestMode()) {
            MODEL.tick(System.currentTimeMillis());
        }
        return nextOffer(RECOVERY_REQUEST_OFFER, new PendingValue() {
            public String take() {
                return MODEL.takeRecoveryRequest();
            }
        });
    }

    public static String takeFaultAlert() {
        return nextOffer(FAULT_ALERT_OFFER, new PendingValue() {
            public String take() {
                return MODEL.takeFaultAlert();
            }
        });
    }

    public static String takeSafeStopRequest() {
        return nextOffer(SAFE_STOP_OFFER, new PendingValue() {
            public String take() {
                return MODEL.takeSafeStopRequest();
            }
        });
    }

    public static String takeRecoveryReady() {
        return nextOffer(RECOVERY_READY_OFFER, new PendingValue() {
            public String take() {
                return MODEL.takeRecoveryReady();
            }
        });
    }

    public static String takeRecoveryFailed() {
        return nextOffer(RECOVERY_FAILED_OFFER, new PendingValue() {
            public String take() {
                return MODEL.takeRecoveryFailed();
            }
        });
    }

    public static void observeRotaryFault(
        String eventId,
        String faultCode,
        String reason
    ) {
        MODEL.observeRotaryFault(eventId, reason);
        publishLocalFault(eventId, "ROTARY", faultCode, reason);
    }

    public static void observeRotaryFault(String eventId, String reason) {
        observeRotaryFault(eventId, "ALIGNMENT_TIMEOUT", reason);
    }

    public static void observeLidFault(
        String eventId,
        LidLoaderControllerModelV1.Fault fault
    ) {
        MODEL.observeLidFault(eventId, fault);
        publishLocalFault(eventId, "LID", fault.name(), fault.name());
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
        localStateVersion = 0L;
        FaultInjectionStateV2_1.reset();
        FaultTestControlStateV2_1.reset();
        discardOffers();
    }

    public static void systemReset() {
        MODEL.systemReset();
        localStateVersion = 0L;
        localEpochGeneration++;
        FaultInjectionStateV2_1.reset();
        FaultTestControlStateV2_1.reset();
        discardOffers();
    }

    static FaultSupervisorModelV2_1 modelForTest() {
        return MODEL;
    }

    private static synchronized void publishLocalFault(
        String eventId,
        String subsystem,
        String faultCode,
        String detail
    ) {
        if (eventId == null || eventId.equals(MODEL.getActiveEventId())) {
            return;
        }
        String severity = "WARNING";
        if ("MAGAZINE_EMPTY".equals(faultCode)) {
            severity = "RESOURCE";
        }
        else if (!"ALIGNMENT_TIMEOUT".equals(faultCode) &&
            !"PICK_TIMEOUT".equals(faultCode)) {
            severity = "CRITICAL";
        }
        localStateVersion++;
        String epoch = localEpochGeneration == 0L ? "M3-E01" :
            "M3-E01R" + localEpochGeneration;
        MODEL.onFaultEvent(
            "V2|" + eventId + "|" + epoch + "|" + subsystem + "|" +
            faultCode + "|" + severity + "|-|" + localStateVersion
        );
    }

    private static synchronized String nextOffer(
        BoundedStringSignalOfferV1 offer,
        PendingValue pending
    ) {
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime()
        );
        if (!offer.isPending()) {
            String payload = pending.take();
            if (payload != null) {
                offer.begin(payload, now);
            }
        }
        return offer.nextValue(now);
    }

    private static synchronized void discardOffers() {
        RECOVERY_REQUEST_OFFER.discard();
        FAULT_ALERT_OFFER.discard();
        SAFE_STOP_OFFER.discard();
        RECOVERY_READY_OFFER.discard();
        RECOVERY_FAILED_OFFER.discard();
    }

    private interface PendingValue {
        String take();
    }
}
