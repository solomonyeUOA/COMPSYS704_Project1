/** Deterministic checks for heartbeat confirmation and bounded auto-reset. */
public final class SystemWatchdogSelfTest {
    private static final String[] LOCAL = {
        "Fault Supervisor", "Rotary Controller", "Rotary Plant",
        "Lid Controller", "Lid Plant"
    };

    private SystemWatchdogSelfTest() {
    }

    public static void main(String[] args) {
        testHealthyAndTransientDelay();
        testConfirmedTimeoutRequestsOneReset();
        testPersistentFaultHonoursCooldown();
        System.out.println("SYSTEM_WATCHDOG_SELF_TEST_PASSED");
    }

    private static void testHealthyAndTransientDelay() {
        SystemWatchdogV1.resetForTest(0L);
        observeAllExcept(null, 0L);
        SystemWatchdogV1.tickForTest(0L);
        require("HEALTHY".equals(SystemWatchdogV1.snapshot().health),
            "fresh heartbeats must be healthy");

        observeAllExcept("Rotary Plant", 4500L);
        SystemWatchdogV1.tickForTest(4500L);
        require("WARNING".equals(SystemWatchdogV1.snapshot().health),
            "one timeout sample must only warn");
        SystemWatchdogV1.observeAt("Rotary Plant", true, "recovered", 5000L);
        observeAllExcept("Rotary Plant", 5000L);
        SystemWatchdogV1.tickForTest(5000L);
        require("HEALTHY".equals(SystemWatchdogV1.snapshot().health),
            "a transient delay must clear without reset");
        require(SystemWatchdogV1.snapshot().resetCount == 0,
            "transient delay must not reset");
    }

    private static void testConfirmedTimeoutRequestsOneReset() {
        SystemWatchdogV1.resetForTest(0L);
        observeAllExcept(null, 0L);
        SystemWatchdogV1.tickForTest(0L);
        missRotaryPlant(4500L);
        missRotaryPlant(5000L);
        missRotaryPlant(5500L);
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("RESETTING".equals(snapshot.health),
            "three timeout samples must request reset");
        require(snapshot.resetCount == 1,
            "confirmed fault must request exactly one reset");
        require("Rotary Plant".equals(snapshot.faultComponent),
            "fault component must be retained");
        require(snapshot.pendingResetId != null &&
            snapshot.pendingResetId.matches("RST[0-9]{4,}"),
            "reset request must use the existing reset contract");
    }

    private static void testPersistentFaultHonoursCooldown() {
        SystemWatchdogV1.onSystemResetAcceptedAt("RST5500", 6000L);
        observeAllExcept("Rotary Plant", 6500L);
        SystemWatchdogV1.tickForTest(6500L);
        missRotaryPlant(13000L);
        missRotaryPlant(13500L);
        missRotaryPlant(14000L);
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("FAULT".equals(snapshot.health),
            "persistent fault during cooldown must remain visible");
        require(snapshot.resetCount == 1,
            "cooldown must suppress repeated reset");
        require(snapshot.action.contains("cooldown"),
            "GUI action must explain cooldown suppression");
    }

    private static void missRotaryPlant(long nowMs) {
        observeAllExcept("Rotary Plant", nowMs);
        SystemWatchdogV1.tickForTest(nowMs);
    }

    private static void observeAllExcept(String excluded, long nowMs) {
        for (String component : LOCAL) {
            if (!component.equals(excluded)) {
                SystemWatchdogV1.observeAt(component, true, "healthy", nowMs);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
