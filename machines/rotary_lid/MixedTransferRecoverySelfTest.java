/** Ambiguous transfer faults remain held without an operator bypass. */
public final class MixedTransferRecoverySelfTest {
    public static void main(String[] args) {
        for (int i = 0; i < 1000; i++) {
            FaultSupervisorModelV2_1 model = new FaultSupervisorModelV2_1();
            acceptHeld(model, "M2-TRANSFER-" + i, "PHOTO_EYE_FAILURE", 10000 + i * 3);
            model = new FaultSupervisorModelV2_1();
            acceptHeld(model, "M2-UNLOADER-" + i, "DEPARTURE_TIMEOUT", i * 3 + 1);
        }
        System.out.println("MixedTransferRecoverySelfTest PASS: 2000 safe holds without operator bypass");
    }
    private static void acceptHeld(FaultSupervisorModelV2_1 model, String id, String fault, long version) {
        if (!model.onFaultEvent("V2|" + id + "|E01|TRANSFER|" + fault + "|CRITICAL|B|" + version))
            throw new AssertionError("independent source version rejected");
        if (!model.onSafeStopAck("V2|" + id + "|E01|SAFE_STOPPED|" + version))
            throw new AssertionError("safe stop rejected");
        if (model.recordManualEvidence(new ManualReconciliationEvidenceV2_1(
                id, "E01", "TRANSFER", "B", version, "test", "POSITION_AND_BOTTLE_RECONCILED")))
            throw new AssertionError("operator bypass accepted");
        FaultPolicyV2_1 policy = FaultPolicyV2_1.select(FaultProtocolV2_1.parseFaultEvent(
            "V2|" + id + "|E01|TRANSFER|" + fault + "|CRITICAL|B|" + version));
        if (model.confirmManualControllerEvidence(id, "E01", policy.safeEvidence,
                policy.serviceEvidence, version + 1)) throw new AssertionError("manual evidence accepted");
        if (model.onResumeDecision("V2|" + id + "|E01|RESUME|GUI_TEST_APPROVAL|" + (version + 1)) ||
                model.getState() != FaultSupervisorModelV2_1.State.LOCKED_OUT)
            throw new AssertionError("unsafe resume accepted");
    }
}
