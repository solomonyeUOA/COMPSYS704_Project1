/** Deterministic checks for strict, bounded watchdog intervention. */
public final class SystemWatchdogSelfTest {
    private static final String[] LOCAL = {
        "Fault Supervisor", "Rotary Controller", "Rotary Plant",
        "Lid Controller", "Lid Plant"
    };

    private SystemWatchdogSelfTest() {
    }

    public static void main(String[] args) {
        testManualResetRequest();
        testOffDisablesDetectionAndAutomaticReset();
        testHealthyAndTransientDelay();
        testConfirmedTimeoutRequestsOneReset();
        testSuccessfulRecoveryIsVerified();
        testUnclearFaultEntersSafeErrorWithoutReset();
        testSupervisedFaultDoesNotPreemptRecovery();
        testUnrelatedTimeoutStillResetsDuringRecovery();
        testPersistentFaultStopsAfterBoundedAttempts();
        System.out.println("SYSTEM_WATCHDOG_SELF_TEST_PASSED");
    }

    private static void testManualResetRequest() {
        SystemWatchdogV1.resetForTest(1000L);
        SystemWatchdogV1.setActive(false);
        require(SystemWatchdogV1.requestManualSystemReset(),
            "explicit manual reset remains available while watchdog is off");
        String request = SystemWatchdogV1.nextSystemResetRequest();
        require(request != null && request.startsWith("RST"),
            "manual reset is emitted to M1");
        require("RESETTING".equals(SystemWatchdogV1.snapshot().health),
            "manual reset is visible as RESETTING");
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
        require(snapshot.resetCount == 1 && snapshot.recoveryAttempt == 1,
            "confirmed fault must request exactly one bounded reset");
        require("Rotary Plant".equals(snapshot.faultComponent),
            "fault component must be retained");
        require(snapshot.pendingResetId != null &&
            snapshot.pendingResetId.matches("RST[0-9]{4,}"),
            "reset request must use the existing reset contract");
        require("Watchdog Intervention".equals(snapshot.notificationTitle),
            "intervention must publish a GUI alert");
    }

    private static void testSuccessfulRecoveryIsVerified() {
        SystemWatchdogV1.onSystemResetAcceptedAt("RST5500", 6000L);
        observeAllExcept(null, 6500L);
        SystemWatchdogV1.tickForTest(6500L);
        observeAllExcept(null, 7000L);
        SystemWatchdogV1.tickForTest(7000L);
        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("HEALTHY".equals(snapshot.health),
            "two healthy verification samples complete recovery");
        require(snapshot.recoveryAttempt == 0,
            "successful recovery clears the incident attempt counter");
        require("Watchdog Recovery Successful".equals(
            snapshot.notificationTitle),
            "successful verification must publish a GUI alert");
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
            snapshot.manualInterventionRequired,
            "unclear fault must enter latched SAFE / ERROR");
        require(snapshot.resetCount == 0 && snapshot.pendingResetId == null,
            "unclear fault must not trigger reset");
        require("Watchdog Recovery Failed".equals(snapshot.notificationTitle),
            "SAFE / ERROR transition must publish a failure alert");
        SystemWatchdogV1.setActive(false);
        SystemWatchdogV1.setActive(true);
        require(SystemWatchdogV1.snapshot().manualInterventionRequired,
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

    private static void testUnrelatedTimeoutStillResetsDuringRecovery() {
        FaultSupervisorStateV2_1.reset();
        require(FaultSupervisorStateV2_1.onFaultEvent(
            "V2|WD-LID-2|WD-E2|LID|PICK_TIMEOUT|WARNING|-|1"
        ), "supervisor accepts second controlled lid fault");
        SystemWatchdogV1.resetForTest(0L);
        observeAllExcept(null, 0L);
        SystemWatchdogV1.tickForTest(0L);
        missRotaryPlant(4500L);
        missRotaryPlant(5000L);
        missRotaryPlant(5500L);
        require(SystemWatchdogV1.snapshot().resetCount == 1,
            "unrelated timeout must still request a bounded reset");
        FaultSupervisorStateV2_1.reset();
    }

    private static void testPersistentFaultStopsAfterBoundedAttempts() {
        SystemWatchdogV1.resetForTest(0L);
        observeAllExcept(null, 0L);
        SystemWatchdogV1.tickForTest(0L);
        missRotaryPlant(4500L);
        missRotaryPlant(5000L);
        missRotaryPlant(5500L);

        acceptThenKeepRotaryMissing("RST5500", 6000L, 36000L);
        require(SystemWatchdogV1.snapshot().recoveryAttempt == 2,
            "persistent timeout requests second attempt after cooldown");
        acceptThenKeepRotaryMissing("RST36000", 36500L, 66500L);
        require(SystemWatchdogV1.snapshot().recoveryAttempt == 3,
            "persistent timeout requests third and final attempt");
        SystemWatchdogV1.onSystemResetAcceptedAt("RST66500", 67000L);
        for (long now = 67500L; now <= 97000L; now += 500L) {
            missRotaryPlant(now);
        }

        SystemWatchdogV1.Snapshot snapshot = SystemWatchdogV1.snapshot();
        require("FAULT".equals(snapshot.health) &&
            snapshot.manualInterventionRequired,
            "persistent failure must latch SAFE / ERROR");
        require(snapshot.resetCount == 3 && snapshot.pendingResetId == null,
            "automatic reset must stop at the configured maximum");
        require(snapshot.action.contains("SAFE / ERROR"),
            "GUI action must require manual intervention");
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
