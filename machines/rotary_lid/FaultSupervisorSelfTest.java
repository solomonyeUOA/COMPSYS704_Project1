/** Framework-free protocol and recovery-policy checks for the M3 IP. */
public final class FaultSupervisorSelfTest {
    private FaultSupervisorSelfTest() {
    }

    public static void main(String[] args) {
        testVerifiedArrivalRetry();
        testNoBlindRetryForDepartureFault();
        testDuplicateEventDoesNotResend();
        testEpochAndVersionChecks();
        testInvalidRecoveryEvidence();
        testLocalRotaryAndLidPolicies();
        testConcurrentFaultPriority();
        System.out.println("FaultSupervisorSelfTest PASSED");
    }

    private static void testVerifiedArrivalRetry() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E1", "A", "ARRIVAL_TIMEOUT", 4)),
            "arrival event accepted");
        require(model.takeRecoveryRequest() == null,
            "safe-stop evidence is required before a retry");
        require(model.confirmSafeStop("E1", true), "safe stop accepted");
        require(
            "V2|E1|A|RETRY_TRANSFER|1|4".equals(model.takeRecoveryRequest()),
            "one versioned recovery request emitted"
        );
        require(model.onRecoveryAck("V2|E1|A|1|ACCEPTED|OK|4"),
            "matching ACK accepted");
        require(model.onRecoveryResult(
            "V2|E1|A|1|SUCCESS|motor_off+occupancy_consistent|" +
            "arrival_confirmed|5"
        ), "independent evidence completes recovery");
        require(model.getState() == FaultSupervisorModelV2_1.State.RECOVERY_READY,
            "M1 still owns final resume");
    }

    private static void testNoBlindRetryForDepartureFault() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E2", "A", "DEPARTURE_TIMEOUT", 6)),
            "departure event accepted");
        require(model.getState() == FaultSupervisorModelV2_1.State.MANUAL_RECOVERY,
            "ambiguous departure requires manual reconciliation");
        require(!model.confirmSafeStop("E2", true),
            "safe stop alone cannot authorize a blind retry");
    }

    private static void testDuplicateEventDoesNotResend() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        String event = event("E3", "A", "ARRIVAL_TIMEOUT", 7);
        require(model.onTransferFault(event), "event accepted");
        require(model.confirmSafeStop("E3", true), "retry authorized");
        require(model.takeRecoveryRequest() != null, "first request emitted");
        require(model.onTransferFault(event), "duplicate event is idempotent");
        require(model.takeRecoveryRequest() == null,
            "duplicate event must not repeat physical work");
    }

    private static void testEpochAndVersionChecks() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E4", "A", "ARRIVAL_TIMEOUT", 20)),
            "first snapshot may start at any version");
        require(!model.onTransferFault(event("E5", "A", "ARRIVAL_TIMEOUT", 22)),
            "version gap requires a snapshot");

        model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E6", "A", "ARRIVAL_TIMEOUT", 20)),
            "old epoch accepted");
        require(model.onTransferFault(event("E7", "B", "ARRIVAL_TIMEOUT", 1)),
            "new source epoch resets its version baseline");
    }

    private static void testInvalidRecoveryEvidence() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E8", "A", "ARRIVAL_TIMEOUT", 9)),
            "event accepted");
        require(model.confirmSafeStop("E8", true), "retry authorized");
        model.takeRecoveryRequest();
        require(model.onRecoveryAck("V2|E8|A|1|ACCEPTED|OK|9"),
            "ACK accepted");
        require(!model.onRecoveryResult(
            "V2|E8|A|1|SUCCESS|motor_off|arrival_confirmed|10"
        ), "missing occupancy evidence is rejected");
        require(model.getState() == FaultSupervisorModelV2_1.State.FAILED,
            "invalid result escalates instead of resuming");
    }

    private static void testLocalRotaryAndLidPolicies() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        model.observeRotaryFault("R1", "table alignment timeout");
        require(!model.authorizeRotaryReset("R1",
            new RotaryRecoveryEvidenceV1(true, true, false)),
            "rotary reset rejects incomplete independent evidence");
        require(model.authorizeRotaryReset("R1",
            new RotaryRecoveryEvidenceV1(true, true, true)),
            "reconciled rotary fault may be reset without auto-rehome");
        model.resolveLocalFault("ROTARY", "R1");

        model.observeLidFault("L1",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT);
        LidRecoveryEvidenceV1 pickEvidence = new LidRecoveryEvidenceV1(
            true, true, true, false, false
        );
        require(model.authorizeLidReset("L1",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT, pickEvidence),
            "one evidence-backed pick retry is authorized");
        require(!model.authorizeLidReset("L1",
            LidLoaderControllerModelV1.Fault.PICK_TIMEOUT, pickEvidence),
            "a second pick retry for the same event is rejected");

        model.observeLidFault("L2",
            LidLoaderControllerModelV1.Fault.MAGAZINE_EMPTY);
        require(model.authorizeLidReset("L2",
            LidLoaderControllerModelV1.Fault.MAGAZINE_EMPTY,
            new LidRecoveryEvidenceV1(true, false, false, false, false)),
            "resource restoration clears magazine-empty supervision");
    }

    private static void testConcurrentFaultPriority() {
        FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
        require(model.onTransferFault(event("E9", "A", "ARRIVAL_TIMEOUT", 30)),
            "first warning fault accepted");
        require(!model.onTransferFault(
            "V2|E10|A|TRANSFER|ARRIVAL_TIMEOUT|WARNING|B001|31"),
            "a second warning cannot replace active recovery");
        require("E9".equals(model.getActiveEventId()),
            "first recovery remains active");
        require(model.onTransferFault(
            "V2|E11|A|TRANSFER|POSITION_CONFLICT|CRITICAL|B001|31"),
            "critical fault deterministically pre-empts warning");
        require("E11".equals(model.getActiveEventId()),
            "critical event becomes active");
    }

    private static String event(
        String eventId,
        String epoch,
        String fault,
        long version
    ) {
        return "V2|" + eventId + "|" + epoch + "|TRANSFER|" + fault +
            "|WARNING|B001|" + version;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
