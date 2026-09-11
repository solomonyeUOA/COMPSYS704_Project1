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
        testRuntimeWatchdog();
        testLateMessagesAfterSystemReset();
        testSystemResetFromReachableStates();
        testBoundedFacadeTransportAndReset();
        testLocalGpRecoveryBoundary();
        testConcurrentFaultHold();
        testMalformedAndUnknownEvents();
        testGuiEnablementRules();
        testGuiHotModeSwitch();
        testMonitoringSnapshot();
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
        require(model.getLatestStateVersion() == 5,
            "resume correlation uses the verified controller version");
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

    private static void testRuntimeWatchdog() {
        FaultSupervisorModelV2_1 safeStop =
            new FaultSupervisorModelV2_1();
        safeStop.onFaultEvent(event(
            "W1", "A", "ROTARY", "MOTOR_STALL", "CRITICAL", 1
        ));
        safeStop.tick(safeStop.getStateEnteredAtMs() +
            FaultSupervisorModelV2_1.SAFE_STOP_TIMEOUT_MS);
        require(safeStop.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "runtime watchdog detects missing safe-stop acknowledgement");

        FaultSupervisorModelV2_1 ack = new FaultSupervisorModelV2_1();
        ack.onTransferFault(event(
            "W2", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        ));
        ack.tick(ack.getStateEnteredAtMs() +
            FaultSupervisorModelV2_1.ACK_TIMEOUT_MS);
        require(ack.getState() == FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "runtime watchdog detects missing recovery acknowledgement");

        FaultSupervisorModelV2_1 result = new FaultSupervisorModelV2_1();
        result.onTransferFault(event(
            "W3", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        ));
        result.takeRecoveryRequest();
        result.onRecoveryAck("V2|W3|A|1|ACCEPTED|OK|1");
        result.tick(result.getStateEnteredAtMs() +
            FaultSupervisorModelV2_1.RESULT_TIMEOUT_MS);
        require(result.getState() ==
            FaultSupervisorModelV2_1.State.LOCKED_OUT,
            "runtime watchdog detects missing recovery result");
    }

    private static void testLateMessagesAfterSystemReset() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            "RST-LATE", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        )), "pre-reset event accepted");
        require(model.takeRecoveryRequest() != null,
            "pre-reset recovery request emitted");
        model.systemReset();

        require(!model.onRecoveryAck(
            "V2|RST-LATE|A|1|ACCEPTED|OK|1"
        ), "late ACK is rejected after reset");
        require(model.getState() == FaultSupervisorModelV2_1.State.IDLE,
            "late ACK cannot lock out an idle reset system");
        require(!model.onRecoveryResult(
            "V2|RST-LATE|A|1|SUCCESS|motor_off+occupancy_consistent|" +
            "arrival_confirmed|2"
        ), "late result is rejected after reset");
        require(model.getState() == FaultSupervisorModelV2_1.State.IDLE,
            "late result cannot lock out an idle reset system");
        require(model.onTransferFault(event(
            "RST-NEXT", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 2
        )), "newer work is accepted after stale reset traffic");
    }

    private static void testSystemResetFromReachableStates() {
        FaultSupervisorModelV2_1 idle = new FaultSupervisorModelV2_1();
        assertSystemReset(idle, "IDLE");

        FaultSupervisorModelV2_1 waitingAck = transferRetry("RST-ACK");
        require(waitingAck.getState() ==
            FaultSupervisorModelV2_1.State.WAITING_ACK,
            "precondition reaches WAITING_ACK");
        assertSystemReset(waitingAck, "WAITING_ACK");

        FaultSupervisorModelV2_1 waitingResult =
            transferRetry("RST-RESULT");
        waitingResult.takeRecoveryRequest();
        require(waitingResult.onRecoveryAck(
            "V2|RST-RESULT|A|1|ACCEPTED|route_clear|1"
        ), "precondition ACK reaches WAITING_RESULT");
        assertSystemReset(waitingResult, "WAITING_RESULT");

        FaultSupervisorModelV2_1 resourceWait =
            new FaultSupervisorModelV2_1();
        require(resourceWait.onFaultEvent(event(
            "RST-RESOURCE", "A", "LID", "MAGAZINE_EMPTY", "RESOURCE", 1
        )), "precondition reaches RESOURCE_WAIT");
        assertSystemReset(resourceWait, "RESOURCE_WAIT");

        FaultSupervisorModelV2_1 recoveryReady =
            transferRetry("RST-READY");
        recoveryReady.takeRecoveryRequest();
        require(recoveryReady.onRecoveryAck(
            "V2|RST-READY|A|1|ACCEPTED|route_clear|1"
        ), "precondition ready ACK accepted");
        require(recoveryReady.onRecoveryResult(
            "V2|RST-READY|A|1|SUCCESS|" +
            "motor_off+occupancy_consistent|arrival_confirmed|2"
        ), "precondition reaches RECOVERY_READY");
        assertSystemReset(recoveryReady, "RECOVERY_READY");

        FaultSupervisorModelV2_1 waitingSafeStop =
            new FaultSupervisorModelV2_1();
        require(waitingSafeStop.onFaultEvent(event(
            "RST-STOP", "A", "ROTARY", "MOTOR_STALL", "CRITICAL", 1
        )), "precondition reaches WAITING_SAFE_STOP");
        assertSystemReset(waitingSafeStop, "WAITING_SAFE_STOP");

        FaultSupervisorModelV2_1 lockedOut =
            new FaultSupervisorModelV2_1();
        require(lockedOut.onFaultEvent(event(
            "RST-LOCK", "A", "ROTARY", "MOTOR_STALL", "CRITICAL", 1
        )), "precondition lockout fault accepted");
        require(lockedOut.onSafeStopAck(
            "V2|RST-LOCK|A|SAFE_STOPPED|1"
        ), "precondition reaches LOCKED_OUT");
        assertSystemReset(lockedOut, "LOCKED_OUT");

        FaultSupervisorModelV2_1 local = new FaultSupervisorModelV2_1();
        local.observeRotaryFault("RST-LOCAL-R", "alignment timeout");
        local.observeLidFault(
            "RST-LOCAL-L", LidLoaderControllerModelV1.Fault.PICK_TIMEOUT
        );
        local.systemReset();
        require("ROTARY=READY; LID=READY".equals(local.getLocalSummary()),
            "system reset clears local GP fault decisions");
    }

    private static FaultSupervisorModelV2_1 transferRetry(String eventId) {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event(
            eventId, "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        )), "transfer retry precondition accepted");
        return model;
    }

    private static void assertSystemReset(
        FaultSupervisorModelV2_1 model,
        String sourceState
    ) {
        model.systemReset();
        require(model.getState() == FaultSupervisorModelV2_1.State.IDLE,
            "system reset clears " + sourceState);
        require("-".equals(model.getActiveEventId()) &&
            model.getActiveAttempt() == 0,
            "system reset clears active correlation from " + sourceState);
        require(model.takeRecoveryRequest() == null &&
            model.takeFaultAlert() == null &&
            model.takeSafeStopRequest() == null &&
            model.takeRecoveryReady() == null &&
            model.takeRecoveryFailed() == null,
            "system reset clears pending outputs from " + sourceState);
    }

    private static void testBoundedFacadeTransportAndReset() {
        FaultSupervisorStateV2_1.reset();
        require(FaultSupervisorStateV2_1.onTransferFault(event(
            "FAC-1", "A", "TRANSFER", "ARRIVAL_TIMEOUT", "WARNING", 1
        )), "facade event accepted");
        String request = FaultSupervisorStateV2_1.takeRecoveryRequest();
        require("V2|FAC-1|A|RETRY_TRANSFER|1|1".equals(request),
            "facade emits the correlated recovery request");
        require(request.equals(
            FaultSupervisorStateV2_1.takeRecoveryRequest()
        ), "recovery request remains PRESENT during its hold window");
        require(FaultSupervisorStateV2_1.onRecoveryAck(
            "V2|FAC-1|A|1|ACCEPTED|route_clear|1"
        ), "facade accepts the matching ACK");
        require(FaultSupervisorStateV2_1.takeRecoveryRequest() == null,
            "matching ACK cancels remaining request copies");

        FaultSupervisorStateV2_1.systemReset();
        require(FaultSupervisorStateV2_1.takeRecoveryRequest() == null &&
            FaultSupervisorStateV2_1.takeFaultAlert() == null &&
            FaultSupervisorStateV2_1.takeSafeStopRequest() == null &&
            FaultSupervisorStateV2_1.takeRecoveryReady() == null &&
            FaultSupervisorStateV2_1.takeRecoveryFailed() == null,
            "system reset discards every active facade offer");
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

    private static void testGuiHotModeSwitch() {
        FaultSupervisorStateV2_1.reset();
        FaultGuiActionsV2_1.setTestMode(false);
        require(!FaultGuiActionsV2_1.perform("inject", "ARRIVAL_TIMEOUT"),
            "live mode rejects test injection at the action boundary");
        FaultGuiActionsV2_1.setTestMode(true);
        require(FaultGuiActionsV2_1.perform("inject", "ARRIVAL_TIMEOUT"),
            "hot test-mode switch enables controlled injection");
        FaultGuiActionsV2_1.setTestMode(false);
        require(!FaultGuiActionsV2_1.perform("safe-stop", null),
            "switching back to live immediately blocks test actions");
        require(FaultGuiActionsV2_1.perform("reset", null),
            "reset remains available in live mode");
    }

    private static void testMonitoringSnapshot() {
        FaultSupervisorStateV2_1.reset();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.SUPERVISOR, true, "IDLE"
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.ROTARY_CONTROLLER, true, "READY"
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.ROTARY_PLANT, true, "READY"
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.LID_CONTROLLER, true, "READY"
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.LID_PLANT, true, "IDLE"
        );
        FaultMonitoringStateV2_1.heartbeat(
            FaultMonitoringStateV2_1.GUI_WORKER, true, "TEST"
        );
        SystemWatchdogV1.tickForTest(System.currentTimeMillis() + 500L);
        FaultMonitoringStateV2_1.Snapshot idle =
            FaultMonitoringStateV2_1.snapshot();
        require("HEALTHY".equals(idle.systemHealth),
            "idle monitored M3 scope is healthy");
        require(idle.components.length == 10,
            "global monitoring exposes every declared component boundary");
        require("IDLE".equals(FaultMonitoringPresentationV2_1.displayState(
            component(idle, FaultMonitoringStateV2_1.SUPERVISOR), idle
        )), "dynamic view maps idle supervisor state");
        FaultMonitoringStateV2_1.ComponentSnapshot running =
            new FaultMonitoringStateV2_1.ComponentSnapshot(
                "TEST", "M3", "RUNNING", "RESPONSIVE", 0L, 0L, "test"
            );
        FaultMonitoringStateV2_1.ComponentSnapshot waiting =
            new FaultMonitoringStateV2_1.ComponentSnapshot(
                "TEST", "M3", "WAITING", "RESPONSIVE", 0L, 0L, "test"
            );
        FaultMonitoringStateV2_1.ComponentSnapshot unresponsive =
            new FaultMonitoringStateV2_1.ComponentSnapshot(
                "TEST", "M3", "RUNNING", "UNRESPONSIVE", 5000L, 1000L,
                "test"
            );
        require("RUNNING".equals(FaultMonitoringPresentationV2_1.displayState(
            running, idle)), "dynamic view preserves running backend state");
        require("WAITING".equals(FaultMonitoringPresentationV2_1.displayState(
            waiting, idle)), "dynamic view preserves waiting backend state");
        require("UNRESPONSIVE".equals(
            FaultMonitoringPresentationV2_1.displayState(unresponsive, idle)
        ), "heartbeat timeout overrides stale running state");

        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|MON-1|MONITOR|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B-MON|1"
        ), "monitoring test accepts transfer fault");
        FaultMonitoringStateV2_1.Snapshot fault =
            FaultMonitoringStateV2_1.snapshot();
        require("WARNING".equals(fault.systemHealth),
            "isolated recoverable fault degrades rather than crashes the system");
        require(fault.faults == 1 && fault.maximumAttempts == 1,
            "fault snapshot exposes active fault and bounded retry budget");
        require("RECOVERING 1/1".equals(FaultMonitoringPresentationV2_1.displayState(
            component(fault, FaultMonitoringStateV2_1.M2_LINK), fault
        )), "dynamic view exposes bounded recovery attempt");

        FaultSupervisorStateV2_1.takeRecoveryRequest();
        require(FaultSupervisorStateV2_1.onRecoveryAck(
            "V2|MON-1|MONITOR|1|ACCEPTED|route_clear|1"
        ), "monitoring recovery ACK accepted");
        FaultMonitoringStateV2_1.Snapshot recovering =
            FaultMonitoringStateV2_1.snapshot();
        require("RECOVERING".equals(FaultMonitoringPresentationV2_1.displayState(
            component(recovering, FaultMonitoringStateV2_1.SUPERVISOR),
            recovering
        )), "dynamic view follows recovery-in-progress state");
        require(FaultSupervisorStateV2_1.onRecoveryResult(
            "V2|MON-1|MONITOR|1|SUCCESS|motor_off+occupancy_consistent|" +
            "arrival_confirmed|2"
        ), "monitoring recovery result accepted");
        FaultMonitoringStateV2_1.Snapshot verified =
            FaultMonitoringStateV2_1.snapshot();
        require("VERIFIED".equals(FaultMonitoringPresentationV2_1.displayState(
            component(verified, FaultMonitoringStateV2_1.SUPERVISOR), verified
        )), "dynamic view follows verified recovery state");
        require(FaultSupervisorStateV2_1.onResumeDecision(
            "V2|MON-1|MONITOR|RESUME|test|2"
        ), "M1 resume returns the monitoring flow to idle");
        FaultMonitoringStateV2_1.Snapshot resumed =
            FaultMonitoringStateV2_1.snapshot();
        require("HEALTHY".equals(resumed.systemHealth),
            "resolved recovery returns system health to healthy");

        require(FaultSupervisorStateV2_1.onTransferFault(
            "V2|MON-2|MONITOR|TRANSFER|POSITION_CONFLICT|CRITICAL|B-MON|3"
        ), "second monitoring fault accepted");
        require(FaultSupervisorStateV2_1.onSafeStopAck(
            "V2|MON-2|MONITOR|SAFE_STOPPED|3"
        ), "critical fault enters isolated recovery state");
        FaultMonitoringStateV2_1.Snapshot failed =
            FaultMonitoringStateV2_1.snapshot();
        require("FAULT / ISOLATED".equals(FaultMonitoringPresentationV2_1.displayState(
            component(failed, FaultMonitoringStateV2_1.M2_LINK), failed
        )), "dynamic view exposes terminal recovery failure");
        FaultSupervisorStateV2_1.reset();
    }

    private static FaultMonitoringStateV2_1.ComponentSnapshot component(
        FaultMonitoringStateV2_1.Snapshot snapshot,
        String name
    ) {
        for (FaultMonitoringStateV2_1.ComponentSnapshot component :
            snapshot.components) {
            if (name.equals(component.name)) return component;
        }
        throw new AssertionError("missing monitoring component: " + name);
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
