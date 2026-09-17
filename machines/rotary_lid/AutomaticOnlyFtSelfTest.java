/** Local failover stays automatic; unreplaced faults retain evidence-gated recovery. */
public final class AutomaticOnlyFtSelfTest {
    public static void main(String[] args) throws Exception {
        FaultGuiActionsV2_1.setTestMode(true);
        require(!FaultGuiPolicyV2_1.canResume("IDLE"), "healthy failover needs no operator resume");
        for (java.lang.reflect.Method method : RedundantDriveV1.class.getDeclaredMethods())
            require(!method.getName().equals("repair"), "repair API remains");

        FaultSupervisorModelV2_1 held = new FaultSupervisorModelV2_1();
        require(held.onFaultEvent("V2|H1|E|TRANSFER|POSITION_CONFLICT|CRITICAL|B|1"), "fault accepted");
        require(held.getState() == FaultSupervisorModelV2_1.State.WAITING_SAFE_STOP, "uncertain fault awaits safe stop");
        require(held.onSafeStopAck("V2|H1|E|SAFE_STOPPED|1"), "isolation receipt accepted");
        require(!held.onResumeDecision("V2|H1|E|RESUME|GUI_TEST_APPROVAL|1"), "old approval cannot resume");
        require(held.takeRecoveryReady() == null && held.takeRecoveryRequest() == null, "no false recovery");
        for (int i = 0; i < 1000; i++) held.tick(System.currentTimeMillis() + i * 1000L);
        require(held.getState() == FaultSupervisorModelV2_1.State.LOCKED_OUT, "hold cannot expire into recovery");

        SystemWatchdogV1.resetForTest(0);
        SystemWatchdogV1.setActive(false);
        RedundantDriveV1 drive = new RedundantDriveV1("OFF TEST");
        drive.fail(0, true);
        require(!drive.permitMotion(true, 0) && drive.switchCount() == 0, "OFF cannot select backup");
        SystemWatchdogV1.setActive(true);
        boolean permitted = false;
        for (long now = 1; now <= 200; now++) permitted = drive.permitMotion(true, now);
        require(permitted && drive.activeChannel() == 1, "ON permits feedback-verified healthy backup");
        drive.completed();
        drive.fail(1, true);
        require(!drive.permitMotion(true, 201) && drive.isLocked(), "both failed means safe stop");

        SystemWatchdogV1.resetForTest(0);
        for (int i = 0; i < 100; i++) SystemWatchdogV1.tickForTest(7000L + i * 500L);
        require(SystemWatchdogV1.snapshot().safeError, "no spare process means safe error");
        require(SystemWatchdogV1.nextSystemResetRequest() == null &&
            SystemWatchdogV1.snapshot().resetCount == 0, "no automatic global reset");
        require(FaultSupervisorStateV2_1.isOperationHeld(), "watchdog safe error gates production");
        SystemWatchdogV1.setActive(false);
        SystemWatchdogV1.setActive(true);
        require(SystemWatchdogV1.snapshot().safeError, "toggle cannot clear safe error");
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        FaultGuiActionsV2_1.setTestMode(false);
        System.out.println("AutomaticOnlyFtSelfTest PASS");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
