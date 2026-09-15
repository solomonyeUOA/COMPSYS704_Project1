/** Testable enablement rules used by the policy-aware GUI. */
public final class FaultGuiPolicyV2_1 {
    private FaultGuiPolicyV2_1() {
    }

    public static boolean canInject(String state) {
        return "IDLE".equals(state);
    }

    public static boolean canConfirmSafeStop(String state) {
        return false;
    }

    public static boolean canReturnControllerEvidence(
        String state,
        String decision
    ) {
        return false;
    }

    public static boolean canRecordManualEvidence(String state) {
        return false;
    }

    public static boolean canResume(String state) {
        return false;
    }
}
