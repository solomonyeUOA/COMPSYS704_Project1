/** Framework-free policy, evidence and authority checks for the M3 IP. */
public final class FaultSupervisorSelfTest {
    private FaultSupervisorSelfTest() {
    }

    public static void main(String[] args) {
        testVerifiedArrivalRetry();
        testManualTransferRecovery();
        testResourceWait();
        testLidAndRotaryCatalogue();
        testDuplicateAndAttemptLimit();
        testEpochAndVersionChecks();
        testInvalidRecoveryEvidence();
        testTimeoutEscalation();
        testLocalGpRecoveryBoundary();
        testConcurrentFaultHold();
        testMalformedAndUnknownEvents();
        testGuiEnablementRules();
        System.out.println("FaultSupervisorSelfTest PASSED");
    }

    private static void testVerifiedArrivalRetry() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        String event = event(
            "E1", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 4
        );
        require(model.onTransferFault(event), "arrival event accepted");
        require(event.equals(model.takeFaultAlert()),
            "M1 receives the validated event");
        require(model.takeSafeStopRequest() == null,
            "warning arrival retry does not claim a critical safe stop");
        require("V2|E1|A|RETRY_TRANSFER|1|4".equals(
            model.takeRecoveryRequest()), "one request emitted");
        require(model.takeRecoveryRequest() == null,
            "request is not repeated");
        require(model.onRecoveryAck("V2|E1|A|1|ACCEPTED|route_clear|4"),
            "matching ACK accepted");
        require(model.onRecoveryResult(
            "V2|E1|A|1|SUCCESS|motor_off+occupancy_consistent|" +
            "arrival_confirmed|5"
        ), "independent evidence completes recovery");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.RECOVERY_READY,
            "verified result waits for M1");
        require("V2|E1|A|RECOVERY_READY|5".equals(
            model.takeRecoveryReady()), "M1 receives recovery-ready report");
        require(model.onResumeDecision("V2|E1|A|RESUME|verified|5"),
            "matching M1 resume accepted");
        require(model.getState() == FaultSupervisorModelV2_1.State.IDLE,
            "only M1 returns supervisor to idle");
        FaultSupervisorMetricsV2_1 metrics = model.metricsSnapshot();
        require(metrics.automaticAttempts == 1 &&
            metrics.verifiedRecoveries == 1 &&
            metrics.unsafeActuatorOutputs == 0,
            "automatic recovery metrics are complete");
    }

    private static void testManualTransferRecovery() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "E2", "A", "TRANSFER", "POSITION_CONFLICT", "CRITICAL", 6
        )), "position conflict accepted");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.WAITING_SAFE_STOP,
            "ambiguous position waits for M1 safe stop");
        require("V2|E2|A|SAFE_STOP|6".equals(model.takeSafeStopRequest()),
            "safe stop request is correlated");
        require(model.takeRecoveryRequest() == null,
            "position conflict never requests blind motion");
        require(model.onSafeStopAck("V2|E2|A|SAFE_STOPPED|6"),
            "safe stop ACK accepted");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "manual reconciliation remains locked out");
        require(model.takeRecoveryFailed().contains("NO_AUTOMATIC_ACTION"),
            "M1 receives explicit recovery failure");
        require(!model.confirmManualControllerEvidence(
            "E2", "A", "motor_off+occupancy_consistent",
            "location_confirmed", 7),
            "controller evidence cannot replace operator reconciliation");
        require(model.recordManualEvidence(
            new ManualReconciliationEvidenceV2_1(
                "E2", "A", "TRANSFER", "B001", 6,
                "operator-1", "BOTTLE_LOCATION_RECONCILED"
            )), "manual evidence recorded");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "manual evidence alone does not unlock recovery");
        require(model.confirmManualControllerEvidence(
            "E2", "A", "motor_off+occupancy_consistent",
            "location_confirmed", 7),
            "newer independent Controller evidence is accepted");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.RECOVERY_READY,
            "manual path still waits for M1 resume");
    }

    private static void testResourceWait() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onFaultEvent(event(
            "L1", "L", "LID", "MAGAZINE_EMPTY", "RESOURCE", 2
        )), "empty magazine accepted");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.RESOURCE_WAIT,
            "resource depletion is not a failed retry");
        require(model.getActiveAttempt() == 0,
            "resource wait consumes no attempt");
        require(!model.confirmResourceRestored("L1", false, 3),
            "missing resource evidence rejected");
        require(model.confirmResourceRestored("L1", true, 3),
            "independent availability evidence accepted");
    }

    private static void testLidAndRotaryCatalogue() {
        FaultSupervisorModelV2_1 pick = new FaultSupervisorModelV2_1();
        require(pick.onFaultEvent(event(
            "L2", "L", "LID", "PICK_TIMEOUT", "WARNING", 8
        )), "pick timeout accepted");
        require(pick.getPolicySummary().contains("RETRY_PICK"),
            "pick timeout selects one retry");
        require(pick.takeRecoveryRequest() == null,
            "local retry is never sent to the M2 transfer adapter");
        require(pick.getActiveAttempt() == 1,
            "local retry budget is still recorded");

        FaultSupervisorModelV2_1 alignment =
            new FaultSupervisorModelV2_1();
        require(alignment.onFaultEvent(event(
            "R1", "R", "ROTARY", "ALIGNMENT_TIMEOUT", "WARNING", 3
        )), "alignment timeout accepted");
        require(alignment.getPolicySummary().contains(
            "MANUAL_RECONCILIATION"), "alignment never auto-rehomes");
        require(alignment.takeRecoveryRequest() == null,
            "no rotary motion request emitted");
    }

    private static void testDuplicateAndAttemptLimit() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        String event = event(
            "E3", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 7
        );
        require(model.onTransferFault(event), "event accepted");
        require(model.takeRecoveryRequest() != null, "first request emitted");
        require(model.onTransferFault(event), "duplicate is idempotent");
        require(model.takeRecoveryRequest() == null,
            "duplicate does not repeat physical work");
        require(model.metricsSnapshot().automaticAttempts == 1,
            "attempt counter remains bounded at one");
    }

    private static void testEpochAndVersionChecks() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "E4", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 20
        )), "first snapshot may start at any version");
        model.reportAckTimeout();
        require(!model.onTransferFault(event(
            "E5", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 22
        )), "version gap requires a snapshot");

        model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "E6", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 20
        )), "old epoch accepted");
        model.reportAckTimeout();
        require(model.onTransferFault(event(
            "E7", "B", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        )), "new epoch resets its version baseline");
    }

    private static void testInvalidRecoveryEvidence() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "E8", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 9
        )), "event accepted");
        model.takeRecoveryRequest();
        require(model.onRecoveryAck("V2|E8|A|1|ACCEPTED|OK|9"),
            "ACK accepted");
        require(!model.onRecoveryResult(
            "V2|E8|A|1|SUCCESS|motor_off|arrival_confirmed|10"
        ), "missing occupancy evidence rejected");
        require(model.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "invalid evidence escalates without resume");
    }

    private static void testTimeoutEscalation() {
        FaultSupervisorModelV2_1 ackTimeout =
            new FaultSupervisorModelV2_1();
        ackTimeout.onTransferFault(event(
            "T1", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        ));
        ackTimeout.reportAckTimeout();
        require(ackTimeout.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "ACK timeout does not silently resend");

        FaultSupervisorModelV2_1 resultTimeout =
            new FaultSupervisorModelV2_1();
        resultTimeout.onTransferFault(event(
            "T2", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        ));
        resultTimeout.takeRecoveryRequest();
        resultTimeout.onRecoveryAck("V2|T2|A|1|ACCEPTED|OK|1");
        resultTimeout.reportResultTimeout();
        require(resultTimeout.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "result timeout escalates");
    }

    private static void testLocalGpRecoveryBoundary() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        model.observeRotaryFault("R-GP", "alignment timeout");
        require(!model.authorizeRotaryReset("R-GP",
            new RotaryRecoveryEvidenceV1(true, true, false)),
            "rotary reset rejects incomplete evidence");
        require(model.authorizeRotaryReset("R-GP",
            new RotaryRecoveryEvidenceV1(true, true, true)),
            "manual position evidence permits Controller-owned reset");

        model.observeLidFault("L-GP",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT);
        LidRecoveryEvidenceV1 evidence = new LidRecoveryEvidenceV1(
            true, true, true, false, false
        );
        require(model.authorizeLidReset("L-GP",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT, evidence),
            "one evidence-backed pick retry authorized");
        require(!model.authorizeLidReset("L-GP",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT, evidence),
            "second pick retry rejected");
    }

    private static void testConcurrentFaultHold() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "C1", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 30
        )), "first fault accepted");
        model.takeFaultAlert();
        String concurrentEvent = event(
            "C2", "A", "TRANSFER", "POSITION_CONFLICT", "CRITICAL", 31
        );
        require(!model.onTransferFault(concurrentEvent),
            "critical event cannot replace an in-flight physical attempt");
        require("C1".equals(model.getActiveEventId()),
            "accepted action keeps its correlation context");
        require(model.takeSafeStopRequest().contains("C2"),
            "critical concurrent event still requests safe stop");
        require(model.takeRecoveryFailed().contains(
            "C2|A|RECOVERY_FAILED|CONCURRENT_EVENT_HELD|31"),
            "held event is explicitly escalated with its own correlation");
        require(model.onTransferFault(concurrentEvent),
            "repeated held event is idempotent");
        require(model.takeSafeStopRequest() == null,
            "duplicate held event does not repeat the safe-stop request");
    }

    private static void testMalformedAndUnknownEvents() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(!model.onFaultEvent("bad"), "malformed event rejected");
        require(!model.onFaultEvent(
            "V2|U1|A|TRANSFER|UNKNOWN|WARNING|B001|1"
        ), "unknown fault rejected");
        require(!model.onFaultEvent(
            "V2|U2|A|TRANSFER|ARRIVAL_TIMEOUT|CRITICAL|B001|1"
        ), "incorrect severity rejected");
        require(model.metricsSnapshot().rejectedMessages == 3,
            "all invalid messages are measured");
    }

    private static void testGuiEnablementRules() {
        require(FaultGuiPolicyV2_1.canInject("IDLE"),
            "fault injection is test-idle only");
        require(!FaultGuiPolicyV2_1.canResume("WAITING_RESULT"),
            "GUI cannot expose early resume");
        require(FaultGuiPolicyV2_1.canConfirmSafeStop(
            "WAITING_SAFE_STOP"), "safe-stop control follows policy state");
        require(FaultGuiPolicyV2_1.canRecordManualEvidence("LOCKED_OUT"),
            "manual evidence is available only after lockout");
        require(!FaultGuiPolicyV2_1.canReturnControllerEvidence(
            "LOCKED_OUT", "MANUAL_RECONCILIATION_REQUIRED"),
            "controller evidence remains blocked before manual evidence");
        require(FaultGuiPolicyV2_1.canReturnControllerEvidence(
            "LOCKED_OUT", "AWAIT_NEWER_CONTROLLER_EVIDENCE"),
            "newer Controller evidence is enabled after reconciliation");
        require(FaultGuiPolicyV2_1.canResume("RECOVERY_READY"),
            "resume appears only after verified readiness");
    }

    private static String event(
        String eventId,
        String epoch,
        String subsystem,
        String fault,
        String severity,
        long version
    ) {
        return "V2|" + eventId + "|" + epoch + "|" + subsystem + "|" +
            fault + "|" + severity + "|B001|" + version;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
