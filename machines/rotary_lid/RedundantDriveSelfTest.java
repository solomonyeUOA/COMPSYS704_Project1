/** Physical-model regression: failover must complete work, not fabricate feedback. */
public final class RedundantDriveSelfTest {
    public static void main(String[] args) {
        SystemWatchdogV1.setActive(true);
        for (int i = 0; i < 100; i++) {
            rotary(i);
            lid();
        }
        interlocks();
        notifications();
        hardwarePersistence();
        System.out.println("RedundantDriveSelfTest PASS: 100 rotary and 200 lid failovers");
    }

    private static void rotary(int iteration) {
        RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
        RotaryControllerModelV1 controller = new RotaryControllerModelV1();
        String id = "DRIVE" + iteration;
        require(plant.registerContext(id + "|S|200|GEOM_S|PACK_S"), "context");
        require(plant.loadBottle(id), "load");
        require(controller.requestRotation(1, plant.canRotate()), "request");
        require(plant.setMotorCommand(true, 1, 0), "start");
        long duration = RotaryControllerModelV1.ROTATION_TIME_MS;
        long failureTime = duration * (iteration % 9 + 1) / 10;
        long now = 0;
        while (now < duration + RotaryControllerModelV1.ALIGNMENT_TIMEOUT_MS) {
            now++;
            if (now == Math.max(1, failureTime)) plant.drive().fail(0, true);
            plant.tick(now);
            controller.tick(1, plant.isAligned());
            if (controller.getState() == RotaryControllerModelV1.State.DONE) break;
        }
        require(controller.getState() == RotaryControllerModelV1.State.DONE, "controller receives real alignment");
        require(plant.drive().activeChannel() == 1, "standby drives rotary");
        require("DEGRADED".equals(plant.drive().monitoringSnapshot().state), "verified standby remains degraded");
        require(plant.drive().monitoringSnapshot().detail.contains("AUTO_FAILOVER_VERIFIED"), "supervisor sees verified action");
        require(plant.commitRotation(1) && plant.commitRotation(1), "same cycle commit");
        require(plant.getCompletedSteps() == 1, "no duplicate shift");
        require(id.equals(plant.getBottleAt(1).getId()), "bottle preserved");
        require(plant.takeFillOffer() != null && plant.takeFillOffer() == null, "one downstream handoff");
    }

    private static void lid() {
        LidLoaderPlantModelV1 plant = new LidLoaderPlantModelV1(5);
        plant.setPickCommand(true, 0);
        plant.tick(1);
        plant.pickDrive().fail(0, true);
        plant.tick(2);
        require(!plant.isLidPicked(), "switch does not fake pick");
        long picked = 2;
        while (!plant.isLidPicked() && picked < 1000) plant.tick(++picked);
        require(plant.isLidPicked(), "standby completes pick");
        require(plant.getMagazineCount() == 5, "pick does not consume twice");
        plant.setPlaceCommand(true, picked);
        plant.placeDrive().fail(0, true);
        plant.tick(picked + 1);
        require(!plant.isLidPlacedSensorActive(picked + 1), "switch does not fake placement");
        long placed = picked + 1;
        while (!plant.isLidPlacedSensorActive(placed) && placed < picked + 1000) plant.tick(++placed);
        require(plant.isLidPlacedSensorActive(placed), "standby placement feedback");
        plant.tick(placed + 1);
        require(plant.getMagazineCount() == 4, "one lid consumed");
        plant.setPickCommand(false, placed);
        plant.setPickCommand(true, placed + 2);
        plant.pickDrive().fail(1, true);
        plant.tick(placed + 3);
        plant.tick(placed + 10000);
        require(!plant.isLidPicked() && plant.pickDrive().isLocked(), "dual failure holds work");
        require("SAFE_ERROR".equals(plant.pickDrive().monitoringSnapshot().state), "supervisor sees dual failure");
        require(plant.getMagazineCount() == 4, "failure preserves inventory");
        plant.cancelAction();
        require(plant.pickDrive().isLocked(), "abort does not repair drives");
    }

    private static void interlocks() {
        RedundantDriveV1 standby = new RedundantDriveV1("STANDBY TEST");
        standby.fail(1, true);
        require(standby.permitMotion(true) && standby.activeChannel() == 0,
            "standby failure does not change healthy primary");
        standby.fail(0, true);
        for (int i = 0; i < 1000; i++) require(!standby.permitMotion(true), "bounded lockout");
        require(standby.switchCount() == 0, "no cycling through failed channels");
        RedundantDriveV1 drive = new RedundantDriveV1("TEST");
        drive.fail(0, false);
        require(!drive.permitMotion(true), "unisolated failure cannot switch");
        require(drive.switchCount() == 0, "no unsafe switch");
        drive = new RedundantDriveV1("FRESH TEST AXIS");
        drive.fail(0, true);
        require(!drive.permitMotion(false), "unknown position prevents switch");
        require(!drive.permitMotion(true), "safe state cannot auto-clear");
        LidLoaderPlantModelV1 lid = new LidLoaderPlantModelV1(5);
        lid.setPickFault(true);
        lid.pickDrive().fail(0, true);
        lid.setPickCommand(true, 0);
        lid.tick(10000);
        require(!lid.isLidPicked() && lid.pickDrive().switchCount() == 0, "existing fault not bypassed");
    }

    private static void hardwarePersistence() {
        Member3PlantStateV1.reset();
        FaultGuiActionsV2_1.setTestMode(false);
        require(!Member3PlantStateV1.injectDriveFailure("ROTARY", 0), "live mode rejects injection");
        FaultGuiActionsV2_1.setTestMode(true);
        require(Member3PlantStateV1.injectDriveFailure("ROTARY", 0), "test injection");
        FaultMonitoringStateV2_1.Snapshot observed = FaultMonitoringStateV2_1.snapshot();
        require(!"HEALTHY".equals(observed.systemHealth), "damaged drive cannot display system healthy");
        int driveRows = 0;
        for (FaultMonitoringStateV2_1.ComponentSnapshot row : observed.components)
            if ("LOCAL DRIVE STATE".equals(row.heartbeat)) driveRows++;
        require(driveRows == 3, "supervisor includes all three drive groups");
        Member3PlantStateV1.systemReset();
        require(Member3PlantStateV1.driveSnapshot().contains("A=FAILED/NOT ISOLATED"), "reset cannot fabricate isolation feedback");
        Member3PlantStateV1.reset();
        FaultGuiActionsV2_1.setTestMode(false);
    }
    private static void notifications() {
        long cursor = DriveEventsV1.notificationSequence();
        RedundantDriveV1 drive = new RedundantDriveV1("NOTIFICATION TEST");
        drive.fail(0, true);
        require(DriveEventsV1.notificationsAfter(cursor).length == 0, "injection is not detection");
        require(!drive.permitMotion(true, 0), "detection first stops motion");
        for (long now = 1; now <= 100; now++) drive.permitMotion(true, now);
        require(drive.activeChannel() == 1, "automatic verified coupling switch");
        DriveEventsV1.Notification[] notices = DriveEventsV1.notificationsAfter(cursor);
        require(notices.length == 5 && "FAULT_DETECTED".equals(notices[0].action) &&
            "SWITCH_TO_B".equals(notices[4].action), "detection, three phases and switch notices");
        cursor = notices[4].sequence;
        drive.completed();
        drive.completed();
        notices = DriveEventsV1.notificationsAfter(cursor);
        require(notices.length == 1 && "FAILOVER_VERIFIED".equals(notices[0].action), "one verified notice");
        cursor = notices[0].sequence;
        drive.fail(1, true);
        require(!drive.permitMotion(true, 101), "dual failure blocks");
        for (int i = 0; i < 100; i++) drive.permitMotion(true, 102 + i);
        notices = DriveEventsV1.notificationsAfter(cursor);
        require(notices.length == 2 && notices[1].safeStop, "single manual-required alert without flood");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
