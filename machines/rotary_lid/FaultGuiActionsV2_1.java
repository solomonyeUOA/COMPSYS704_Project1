import java.util.concurrent.atomic.AtomicBoolean;

/** Test injection is user-driven; recovery never accepts operator evidence. */
public final class FaultGuiActionsV2_1 {
    private static final AtomicBoolean TEST_MODE = new AtomicBoolean(Boolean.getBoolean("m3.testMode"));
    private FaultGuiActionsV2_1() { }
    public static boolean isTestMode() { return TEST_MODE.get(); }
    public static void setTestMode(boolean enabled) { TEST_MODE.set(enabled); }
    public static boolean perform(String action, String fault) {
        if ("safe-stop".equals(action) || "controller-evidence".equals(action) ||
            "manual-evidence".equals(action) || "resume".equals(action)) return false;
        if ("reset".equals(action)) return SystemWatchdogV1.requestManualSystemReset();
        if (!isTestMode()) return false;
        if ("inject".equals(action)) {
            String rejection = FaultInjectionCatalogV1.rejection(fault);
            if (rejection != null) throw new IllegalStateException(rejection);
            return FaultInjectionStateV2_1.arm(fault);
        }
        if ("cancel-injection".equals(action)) return FaultInjectionStateV2_1.cancel();
        throw new IllegalArgumentException("unknown action: " + action);
    }
}
