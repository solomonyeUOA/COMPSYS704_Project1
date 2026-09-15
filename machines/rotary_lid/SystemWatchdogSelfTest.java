/** Deterministic checks for strict, bounded watchdog intervention. */
public final class SystemWatchdogSelfTest {
    private static final String[] LOCAL = {
        "Fault Supervisor", "Rotary Controller", "Rotary Plant",
        "Lid Controller", "Lid Plant"
    };

    private SystemWatchdogSelfTest() {
    }

    public static void main(String[] args) {
        testOffDisablesDetectionAndAutomaticReset();
        testHealthyAndTransientDelay();
        testUnclearFaultEntersSafeErrorWithoutReset();
        testSupervisedFaultDoesNotPreemptRecovery();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        System.out.println("SYSTEM_WATCHDOG_SELF_TEST_PASSED");
    }



    private static void testOffDisablesDetectionAndAutomaticReset() {
        SystemWatchdogV1.resetForTest(0L);
        SystemWatchdogV1.setActive(false);
        for (long now = 7000L; now <= 12000L; now += 500L) {
            SystemWatchdogV1.tickForTest(now);
        }
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require(!snapshot.active, "watchdog must report OFF");
        require(snapshot.pendingResetId == null && snapshot.resetCount == 0,
            "OFF must not detect faults or request automatic reset");
        require("Monitoring disabled".equals(snapshot.action),
            "OFF must report disabled monitoring");
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





    private static void testUnclearFaultEntersSafeErrorWithoutReset() {
        SystemWatchdogV1.resetForTest(0L);
        for (long now = 0L; now <= 1000L; now += 500L) {
            observeAllExcept("Rotary Plant", now);
            SystemWatchdogV1.observeAt(
                "Rotary Plant", false, "unclassified sensor disagreement", now
            );
            SystemWatchdogV1.tickForTest(now);
        }
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("FAULT".equals(snapshot.health) &&
            snapshot.safeError,
            "unclear fault must enter latched SAFE / ERROR");
        require(snapshot.resetCount == 0 && snapshot.pendingResetId == null,
            "unclear fault must not trigger reset");
        require("Watchdog Recovery Failed".equals(snapshot.notificationTitle),
            "SAFE / ERROR transition must publish a failure alert");
        SystemWatchdogV1.setActive(false);
        SystemWatchdogV1.setActive(true);
        require(SystemWatchdogV1.snapshot().safeError,
            "toggle must not automatically clear SAFE / ERROR");
    }

    private static void testSupervisedFaultDoesNotPreemptRecovery() {
        FaultSupervisorStateV2_1.reset();
        require(FaultSupervisorStateV2_1.onFaultEvent(
            "V2|WD-LID-1|WD-E1|LID|PICK_TIMEOUT|WARNING|-|1"
        ), "supervisor accepts controlled lid fault");
        SystemWatchdogV1.resetForTest(0L);
        for (long now = 0L; now <= 5500L; now += 500L) {
            observeAllExcept("Lid Controller", now);
            SystemWatchdogV1.observeAt(
                "Lid Controller", false, "FAULT", now
            );
            SystemWatchdogV1.tickForTest(now);
        }
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("WARNING".equals(snapshot.health),
            "supervised component fault remains a visible warning");
        require(snapshot.resetCount == 0 && snapshot.pendingResetId == null,
            "watchdog must not alter an active supervisor workflow");
        require("Lid Controller".equals(snapshot.faultComponent),
            "managed fault component remains visible");
        FaultSupervisorStateV2_1.reset();
    }





    private static void acceptThenKeepRotaryMissing(
        String resetId,
        long acceptedAt,
        long retryAt
    ) {
        SystemWatchdogV1.onSystemResetAcceptedAt(resetId, acceptedAt);
        for (long now = acceptedAt + 500L; now <= retryAt; now += 500L) {
            missRotaryPlant(now);
        }
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
