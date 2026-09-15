/** Deterministic mid-action, interlock and failed-feedback regression. */
public final class PhysicalFailoverSelfTest {
    public static void main(String[] args) {
        SystemWatchdogV1.setActive(true);
        for (int i = 1; i < 100; i++) rotary(i);
        for (String fault : new String[] {"STOP_FAILURE", "HOLD_FAILURE", "ISOLATION_FAILURE",
                "ENGAGEMENT_FAILURE", "MECHANICAL_JAM"}) failedMechanism(fault);
        feedback();
        lostLid();
        backupDuringTransfer();
        offDuringTransfer();
        injectionMailbox();
        System.out.println("PhysicalFailoverSelfTest PASS: 99 mid-motion transfers, physical interlocks and injection routing");
    }

    private static void rotary(int percentage) {
        RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
        require(plant.registerContext("PHYSICAL" + percentage + "|S|200|GEOM_S|PACK_S"), "context");
        require(plant.loadBottle("PHYSICAL" + percentage), "load");
        require(plant.setMotorCommand(true, 1, 0), "start");
        long failureAt = RotaryControllerModelV1.ROTATION_TIME_MS * percentage / 100;
        plant.tick(failureAt);
        double retained = plant.angle();
        require(retained > 0 && retained < 60, "partial measured position");
        plant.drive().fail(0, true);
        boolean stop = false, isolated = false, connect = false;
        long now = failureAt;
        for (; now < failureAt + 1000 && !plant.isMovementComplete(); now++) {
            double before = plant.angle();
            plant.tick(now);
            String phase = plant.drive().phase();
            stop |= "STOPPING".equals(phase);
            isolated |= "ISOLATING_PRIMARY".equals(phase);
            connect |= "CONNECTING_BACKUP".equals(phase);
            if (!"RUN".equals(phase)) require(plant.angle() == before, "no powered progress during transfer");
            require(plant.angle() >= before && plant.angle() <= 60, "no rewind or overshoot");
            require(plant.targetAngle() == 60, "original target remains unchanged");
        }
        require(stop && isolated && connect, "all feedback phases observed");
        require(plant.isAligned() && plant.angle() == 60 && plant.speed() == 0, "actual target reached");
        require(plant.drive().activeChannel() == 1 && plant.drive().mechanism().connectedChannel() == 1, "only B connected");
        require(plant.commitRotation(1) && plant.commitRotation(1) && plant.getCompletedSteps() == 1, "exactly one slot shift");
        require(plant.getBottleAt(1) != null && plant.getBottleAt(0) == null, "bottle not lost or duplicated");
    }

    private static void failedMechanism(String fault) {
        RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
        plant.registerContext("FAULT-B1|S|200|GEOM_S|PACK_S");
        plant.loadBottle("FAULT-B1");
        require(plant.setMotorCommand(true, 1, 0), "fault test starts movement");
        plant.tick(100);
        double position = plant.angle();
        plant.drive().mechanism().inject(fault);
        plant.drive().fail(0, true);
        for (long now = 101; now < 2000; now++) plant.tick(now);
        require(plant.drive().isLocked(), fault + " bounded stop");
        require(plant.drive().switchCount() == 0, fault + " no blind switch");
        require(plant.angle() == position && !plant.commitRotation(1), fault + " preserves work");
    }

    private static void feedback() {
        RedundantPositionFeedbackV1 feedback = new RedundantPositionFeedbackV1();
        feedback.failActive();
        require(!feedback.sample(false) && feedback.trusted(), "diagnosed failed sensor selects B but not completion");
        require(feedback.sample(true), "B observes physical alignment");
        feedback.disagree();
        require(!feedback.sample(true) && !feedback.trusted(), "contradiction cannot be voted away with two channels");
        RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
        plant.registerContext("FEEDBACK-B1|S|200|GEOM_S|PACK_S");
        plant.loadBottle("FEEDBACK-B1");
        require(plant.setMotorCommand(true, 1, 0), "feedback test starts movement");
        plant.feedback().disagree();
        plant.tick(50);
        require(plant.drive().isLocked() && plant.angle() == 0, "untrusted position prevents movement");
    }

    private static void lostLid() {
        LidLoaderPlantModelV1 plant = new LidLoaderPlantModelV1(5);
        plant.setPickCommand(true, 0);
        plant.tick(LidLoaderPlantModelV1.PICK_TIME_MS);
        require(plant.isLidPicked(), "held lid");
        plant.setPlaceCommand(true, 500);
        plant.loseHeldLid();
        plant.placeDrive().fail(0, true);
        for (long now = 501; now < 2000; now++) plant.tick(now);
        require(plant.placeDrive().isLocked() && !plant.isLidPlacedSensorActive(2000), "lost load cannot be restored by motor switch");
        require(plant.getMagazineCount() == 5, "no false completed-placement debit");
    }

    private static void backupDuringTransfer() {
        RedundantDriveV1 drive = new RedundantDriveV1("TRANSFER TEST");
        drive.fail(0, true);
        drive.permitMotion(true, 0);
        drive.fail(1, true);
        require(!drive.permitMotion(true, 1) && drive.isLocked(), "backup failure during transfer locks");
        for (int i = 2; i < 1000; i++) require(!drive.permitMotion(true, i), "never infinite retry");
    }

    private static void offDuringTransfer() {
        RedundantDriveV1 drive = new RedundantDriveV1("OFF MID TRANSFER");
        drive.fail(0, true);
        drive.permitMotion(true, 0);
        SystemWatchdogV1.setActive(false);
        for (int i = 1; i < 1000; i++) require(!drive.permitMotion(true, i), "OFF no automatic selection");
        require(drive.switchCount() == 0, "no hidden OFF switch");
        SystemWatchdogV1.setActive(true);
        require(!drive.permitMotion(true, 1001) && drive.isLocked(), "expired transfer cannot resume blindly");
    }

    private static void injectionMailbox() {
        for (String axis : new String[] {"ROTARY", "PICK", "PLACE"}) {
            for (String fault : new String[] {"STOP_FAILURE", "HOLD_FAILURE", "ISOLATION_FAILURE",
                    "ENGAGEMENT_FAILURE", "BACKUP_FAILURE", "MECHANICAL_JAM"}) {
                FaultInjectionStateV2_1.reset();
                String code = axis + "_" + fault;
                require(FaultInjectionStateV2_1.arm(code), "arm " + code);
                require(FaultInjectionStateV2_1.cancel(), "cancel " + code);
                routedFailure(axis, code);
            }
        }
        routedFailure("ROTARY", "ROTARY_FEEDBACK_DISAGREEMENT");
        routedFailure("PLACE", "PLACE_LOAD_LOSS");
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        FaultGuiActionsV2_1.setTestMode(false);
    }

    private static void routedFailure(String axis, String code) {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        FaultGuiActionsV2_1.setTestMode(true);
        require(FaultGuiActionsV2_1.perform("inject", code), "GUI arm " + code);
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        if ("ROTARY".equals(axis)) {
            Member3PlantStateV1.registerBottleContext("ROUTE-B1|S|200|GEOM_S|PACK_S");
            require(Member3PlantStateV1.loadBottle("ROUTE-B1"), "route load");
            Member3PlantStateV1.setRotaryMotor(true, 1);
            for (int step = 1; step < 2000; step++) Member3PlantStateV1.updateRotaryAt(now + step);
            require(!Member3PlantStateV1.isTableAligned(), "no false route alignment");
        } else {
            Member3PlantStateV1.setPickCommand(true);
            if ("PLACE".equals(axis)) {
                Member3PlantStateV1.updateLidLoaderAt(now + LidLoaderPlantModelV1.PICK_TIME_MS + 10);
                require(Member3PlantStateV1.isLidPicked(), "place injection waits for picked lid");
                require(code.equals(FaultInjectionStateV2_1.armedFault()), "not consumed at wrong stage");
                Member3PlantStateV1.setPlaceCommand(true);
                now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            }
            for (int step = 1; step < 2000; step++) Member3PlantStateV1.updateLidLoaderAt(now + step);
            require(Member3PlantStateV1.getLidMagazineCount() == LidLoaderPlantModelV1.MAGAZINE_CAPACITY,
                "failed placement never debits completed inventory");
        }
        require("-".equals(FaultInjectionStateV2_1.armedFault()), "consumed " + code);
        int index = "ROTARY".equals(axis) ? 0 : "PICK".equals(axis) ? 1 : 2;
        require("SAFE_ERROR".equals(Member3PlantStateV1.driveMonitoringSnapshot()[index].state),
            "routed fault reaches safe error " + code);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
