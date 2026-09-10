/** Testable enablement rules used by the policy-aware GUI. */
public final class FaultGuiPolicyV2_1 {
    private FaultGuiPolicyV2_1() {
    }

    public static boolean canInject(String state) {
        return "IDLE".equals(state);
    }

    public static boolean canConfirmSafeStop(String state) {
        return "WAITING_SAFE_STOP".equals(state);
    }

    public static boolean canReturnControllerEvidence(
        String state,
        String decision
    ) {
        return "WAITING_ACK".equals(state) ||
            "RESOURCE_WAIT".equals(state) ||
            ("LOCKED_OUT".equals(state) && decision != null &&
                decision.startsWith("AWAIT_NEWER"));
    }

    public static boolean canRecordManualEvidence(String state) {
        return "LOCKED_OUT".equals(state);
    }

    public static boolean canResume(String state) {
        return "RECOVERY_READY".equals(state);
    }
}
