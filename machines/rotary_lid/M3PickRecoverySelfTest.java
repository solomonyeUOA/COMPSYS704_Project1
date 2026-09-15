/** Exercises actual Plant, controller, retry adapter and existing M1 resume policy. */
public final class M3PickRecoverySelfTest {
    private static final String BOTTLE = "PICK-RETRY-B001";
    public static void main(String[] args) {
        for (int i = 0; i < 20; i++) successful();
        failedRetry();
        unavailable(false);
        unavailable(true);
        resetDuringRetry();
        System.out.println("M3PickRecoverySelfTest PASS: 20 completed retries, bounded failure, home/OFF guards and reset fencing");
    }

    private static void prepare() {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        CoordinatorStateV1.resetForTest();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        M3PickRecoveryV1.tick();
        Member3PlantStateV1.registerBottleContext(BOTTLE + "|S|200|GEOM_S|PACK_S");
        require(Member3PlantStateV1.loadBottle(BOTTLE), "load original bottle");
        rotate(1);
        require(Member3PlantStateV1.markFilled(BOTTLE), "fill before lid station");
        rotate(2);
        require(BOTTLE.equals(Member3PlantStateV1.getBottleWaitingForLidId()), "bottle at lid station");
        FaultGuiActionsV2_1.setTestMode(true);
        require(FaultGuiActionsV2_1.perform("inject", "PICK_TIMEOUT"), "GUI injection");
        require(Member3MachineStateV1.requestLidLoad(BOTTLE, true), "original request admitted");
        String alert = FaultSupervisorStateV2_1.takeFaultAlert();
        require(alert != null && CoordinatorStateV1.recordFtFaultAlert(alert), "M1 receives real alert");
    }

    private static void rotate(long cycle) {
        Member3PlantStateV1.setRotaryMotor(false, 0);
        Member3PlantStateV1.setRotaryMotor(true, cycle);
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        Member3PlantStateV1.updateRotaryAt(now + RotaryControllerModelV1.ROTATION_TIME_MS + 1);
        require(Member3PlantStateV1.commitRotation(cycle), "rotation commit");
    }

    private static void runRetry() {
        M3PickRecoveryV1.tick();
        for (int i = 0; i < 2000 && "WAITING_RESULT".equals(FaultSupervisorStateV2_1.stateName()); i++) {
            Member3PlantStateV1.setPickCommand(Member3MachineStateV1.isLidPickEnabled());
            Member3PlantStateV1.setPlaceCommand(Member3MachineStateV1.isLidPlaceEnabled());
            long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            Member3PlantStateV1.updateLidLoaderAt(now + 1 + i);
            Member3MachineStateV1.tickLidLoader(1, Member3PlantStateV1.isLidPicked(),
                Member3PlantStateV1.isLidPlacedSensorActive());
            M3PickRecoveryV1.tick();
        }
    }

    private static void successful() {
        prepare();
        int before = Member3PlantStateV1.getLidMagazineCount();
        runRetry();
        require("RECOVERY_READY".equals(FaultSupervisorStateV2_1.stateName()), "actual action verified");
        require(Member3MachineStateV1.takeLidDoneBottleId() == null, "DONE held until real M1 resume");
        String ready = FaultSupervisorStateV2_1.takeRecoveryReady();
        require(ready != null && CoordinatorStateV1.recordFtRecoveryReady(ready), "M1 evaluates ready evidence");
        String resume = CoordinatorStateV1.takeFtAutomaticResumeDecision();
        require(resume != null && FaultSupervisorStateV2_1.onResumeDecision(resume), "no operator resume");
        require("IDLE".equals(FaultSupervisorStateV2_1.stateName()), "fault released");
        require(BOTTLE.equals(Member3MachineStateV1.takeLidDoneBottleId()), "same bottle completed");
        require(Member3PlantStateV1.markLidPlaced(BOTTLE), "single original bottle update");
        require(Member3PlantStateV1.getLidMagazineCount() == before - 1, "one inventory debit");
        require(FaultSupervisorStateV2_1.lastCompletedRecoveryAttempt() == 1, "exactly one attempt");
        for (int i = 0; i < 20; i++) M3PickRecoveryV1.tick();
        require(Member3PlantStateV1.getLidMagazineCount() == before - 1, "no replayed physical action");
    }

    private static void failedRetry() {
        prepare();
        Member3PlantStateV1.setPickFault(true);
        runRetry();
        require("LOCKED_OUT".equals(FaultSupervisorStateV2_1.stateName()), "persistent failure locks out");
        for (int i = 0; i < 2000; i++) {
            Member3MachineStateV1.tickLidLoader(10, false, false);
            M3PickRecoveryV1.tick();
        }
        require(!Member3MachineStateV1.isLidPickEnabled() && !Member3MachineStateV1.isLidPlaceEnabled(), "outputs off");
        require(Member3PlantStateV1.getLidMagazineCount() == LidLoaderPlantModelV1.MAGAZINE_CAPACITY, "no false placement");
        require(FaultSupervisorStateV2_1.takeRecoveryReady() == null, "no success from timeout");
    }

    private static void unavailable(boolean off) {
        prepare();
        if (off) SystemWatchdogV1.setActive(false);
        else Member3PlantStateV1.setPickCommand(true); // Not home: cannot safely replay the pick.
        M3PickRecoveryV1.tick();
        require("LOCKED_OUT".equals(FaultSupervisorStateV2_1.stateName()), "unmet interlock has no executable recovery");
        require(!Member3MachineStateV1.isLidPickEnabled(), "no blind retry");
    }

    private static void resetDuringRetry() {
        prepare();
        M3PickRecoveryV1.tick();
        String event = FaultSupervisorStateV2_1.activeEventId(), epoch = FaultSupervisorStateV2_1.activeEpoch();
        Member3MachineStateV1.systemReset();
        Member3PlantStateV1.systemReset();
        M3PickRecoveryV1.tick();
        require(!FaultSupervisorStateV2_1.localPickResult(event, epoch, true), "old completion fenced by reset");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
