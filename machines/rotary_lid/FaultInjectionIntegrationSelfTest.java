/** Verifies GUI requests reach real M3 controllers before the supervisor. */
public final class FaultInjectionIntegrationSelfTest {
    private static final String[] ROTARY_FAULTS = {
        "ALIGNMENT_TIMEOUT", "MOTOR_STALL", "POSITION_SENSOR_FAILURE"
    };
    private static final String[] LID_FAULTS = {
        "MAGAZINE_EMPTY", "PICK_TIMEOUT", "PLACEMENT_TIMEOUT",
        "LID_SENSOR_FAULT"
    };

    private FaultInjectionIntegrationSelfTest() {
    }

    public static void main(String[] args) {
        for (int index = 0; index < ROTARY_FAULTS.length; index++) {
            verifyRotary(ROTARY_FAULTS[index]);
        }
        for (int index = 0; index < LID_FAULTS.length; index++) {
            verifyLid(LID_FAULTS[index], index);
        }
        verifyTransferTransport();
        verifyPositionFeedbackLimits();
        verifyUnifiedDeviceInjections();
        System.out.println("FaultInjectionIntegrationSelfTest PASSED (11 legacy + 4 device faults)");
    }

    private static void verifyUnifiedDeviceInjections() {
        String[] codes = {"ROTARY_DRIVE_FAILURE", "PICK_DRIVE_FAILURE",
            "PLACE_DRIVE_FAILURE", "ROTARY_FEEDBACK_FAILURE"};
        SystemWatchdogV1.resetForTest(System.currentTimeMillis());
        FaultGuiActionsV2_1.setTestMode(true);
        for (int row = 0; row < codes.length; row++) {
            Member3MachineStateV1.reset();
            Member3PlantStateV1.reset();
            String code = codes[row];
            require(FaultGuiActionsV2_1.perform("inject", code), "GUI arms " + code);
            require(FaultGuiActionsV2_1.perform("cancel-injection", null), "pending cancellation");
            require(FaultGuiActionsV2_1.perform("inject", code), "re-arm cancelled device fault");
            require(Member3PlantStateV1.driveMonitoringSnapshot()[row].detail.contains("active=A"),
                "button does not switch hardware");
            long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            if (row == 0 || row == 3) {
                require(Member3PlantStateV1.registerBottleContext("UNIFIED-B1|S|200|GEOM_S|PACK_S"), "context");
                require(Member3PlantStateV1.loadBottle("UNIFIED-B1"), "bottle");
                Member3PlantStateV1.setRotaryMotor(true, 1);
                Member3PlantStateV1.updateRotaryAt(now + 1);
                require(!Member3PlantStateV1.isTableAligned(), "no synthetic completion");
                for (long step = 2; step <= RotaryControllerModelV1.ROTATION_TIME_MS + 120; step++)
                    Member3PlantStateV1.updateRotaryAt(now + step);
                require(Member3PlantStateV1.isTableAligned(), "physical movement completes");
            } else {
                Member3PlantStateV1.setPickCommand(true);
                Member3PlantStateV1.updateLidLoaderAt(now + 1);
                if (row == 2) require(code.equals(FaultInjectionStateV2_1.armedFault()), "place injection waits through pick");
                for (long step = 2; step <= LidLoaderPlantModelV1.PICK_TIME_MS + 120; step++)
                    Member3PlantStateV1.updateLidLoaderAt(now + step);
                require(Member3PlantStateV1.isLidPicked(), "pick completes");
                int inventory = Member3PlantStateV1.getLidMagazineCount();
                Member3PlantStateV1.setPlaceCommand(true);
                now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                for (long step = 1; step <= LidLoaderPlantModelV1.PLACE_TIME_MS + 120; step++)
                    Member3PlantStateV1.updateLidLoaderAt(now + step);
                require(Member3PlantStateV1.getLidMagazineCount() == inventory - 1, "one completed placement");
            }
            require(Member3PlantStateV1.driveMonitoringSnapshot()[row].detail.contains("active=B"),
                "matching runtime action selects standby for " + code);
            require("-".equals(FaultInjectionStateV2_1.armedFault()), "consumed once");
            require("IDLE".equals(FaultSupervisorStateV2_1.stateName()), "no manual approval for local failover");
        }
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        FaultGuiActionsV2_1.setTestMode(false);
    }

    private static void verifyRotary(String faultCode) {
        Member3MachineStateV1.reset();
        if ("POSITION_SENSOR_FAILURE".equals(faultCode)) {
            verifyLocalFailover(faultCode);
            return;
        }
        require(FaultInjectionStateV2_1.arm(faultCode), "arm " + faultCode);
        require(Member3MachineStateV1.requestRotation(true),
            "rotation operation accepts " + faultCode);
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.FAULT, faultCode + " reaches controller");
        require(!Member3MachineStateV1.isRotaryMotorEnabled(),
            faultCode + " de-energises rotary motor");
        require(faultCode.equals(FaultSupervisorStateV2_1.activeFaultCode()),
            faultCode + " reaches supervisor");
        require("-".equals(FaultInjectionStateV2_1.armedFault()),
            faultCode + " is consumed once");
    }

    private static void verifyLid(String faultCode, int index) {
        Member3MachineStateV1.reset();
        require(FaultInjectionStateV2_1.arm(faultCode), "arm " + faultCode);
        require(Member3MachineStateV1.requestLidLoad(
            "TEST-LID-B00" + (index + 1), true
        ), "lid operation accepts " + faultCode);
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.FAULT, faultCode + " reaches controller");
        require(!Member3MachineStateV1.isLidPickEnabled() &&
            !Member3MachineStateV1.isLidPlaceEnabled(),
            faultCode + " de-energises lid actuators");
        require(faultCode.equals(FaultSupervisorStateV2_1.activeFaultCode()),
            faultCode + " reaches supervisor");
        require("-".equals(FaultInjectionStateV2_1.armedFault()),
            faultCode + " is consumed once");
    }

    private static void verifyLocalFailover(String faultCode) {
        Member3PlantStateV1.reset();
        require(Member3PlantStateV1.registerBottleContext("MOTOR-B1|S|200|GEOM_S|PACK_S"), "context");
        require(Member3PlantStateV1.loadBottle("MOTOR-B1"), "load");
        require(FaultInjectionStateV2_1.arm(faultCode), "original GUI injection");
        require(Member3MachineStateV1.requestRotation(true), "request");
        require(Member3MachineStateV1.getRotaryStatus() == Member3MachineStateV1.BUSY,
            "drive failure must not force manual controller recovery before standby runs");
        long cycle = Member3MachineStateV1.getActiveCycleId();
        Member3PlantStateV1.setRotaryMotor(true, cycle);
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        Member3PlantStateV1.updateRotaryAt(now);
        int row = "MOTOR_STALL".equals(faultCode) ? 0 : 3;
        require(Member3PlantStateV1.driveMonitoringSnapshot()[row].detail.contains("active=B"),
            "automatic standby B selection");
        require(!Member3PlantStateV1.isTableAligned(), "switch does not fake completion");
        Member3PlantStateV1.updateRotaryAt(now + RotaryControllerModelV1.ROTATION_TIME_MS + 1);
        Member3MachineStateV1.tickRotary(RotaryControllerModelV1.ROTATION_TIME_MS, false);
        Member3MachineStateV1.tickRotary(1, Member3PlantStateV1.isTableAligned());
        require(Member3MachineStateV1.getRotaryStatus() == Member3MachineStateV1.DONE, "controller completes normally");
        require(Member3PlantStateV1.commitRotation(cycle), "original bottle cycle commits");
        require("IDLE".equals(FaultSupervisorStateV2_1.stateName()), "no artificial manual approval gate");
        require("-".equals(FaultInjectionStateV2_1.armedFault()), "motor injection consumed");
        Member3PlantStateV1.reset();
    }

    private static void verifyPositionFeedbackLimits() {
        SystemWatchdogV1.setActive(true);
        for (int i = 0; i < 200; i++) {
            RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
            require(plant.registerContext("SENSOR-B1|S|200|GEOM_S|PACK_S"), "context");
            require(plant.loadBottle("SENSOR-B1"), "load");
            require(plant.setMotorCommand(true, 1, 0), "start");
            plant.feedback().failActive();
            plant.tick(1);
            require(!plant.isAligned() && !plant.commitRotation(1), "no early completion");
            plant.tick(RotaryControllerModelV1.ROTATION_TIME_MS + 1);
            require(plant.isAligned(), "standby observes completed movement");
            require(plant.commitRotation(1) && plant.commitRotation(1), "idempotent commit");
            require(plant.getCompletedSteps() == 1, "exactly one physical shift");
            plant.feedback().failActive();
            plant.tick(RotaryControllerModelV1.ROTATION_TIME_MS + 2);
            require(!plant.isAligned(), "second failure cannot resurrect A");
        }
        RedundantPositionFeedbackV1 feedback = new RedundantPositionFeedbackV1();
        feedback.failActive();
        SystemWatchdogV1.setActive(false);
        require(!feedback.sample(true), "OFF cannot switch");
        SystemWatchdogV1.setActive(true);
        require(!feedback.sample(false), "backup cannot invent alignment");
        require(feedback.sample(true), "backup reports physical alignment");
    }

    private static void verifyTransferTransport() {
        FaultInjectionStateV2_1.reset();
        require(FaultInjectionStateV2_1.arm("ARRIVAL_TIMEOUT"),
            "arm transfer fault");
        String request = FaultInjectionStateV2_1.nextTransferRequest();
        require(request != null && request.endsWith("|ARRIVAL_TIMEOUT"),
            "transfer fault crosses the M3 SystemJ boundary");
        String requestId = request.split("\\|", -1)[0];
        require(FaultInjectionStateV2_1.onTransferAcknowledgement(
            "V1|" + requestId + "|ACCEPTED"
        ), "M3 accepts matching M2 injection acknowledgement");
        require(FaultInjectionStateV2_1.nextTransferRequest() == null,
            "M3 stops sending after M2 accepts the injection");
        FaultInjectionStateV2_1.consumed("ARRIVAL_TIMEOUT");
        require(FaultInjectionStateV2_1.arm("ARRIVAL_TIMEOUT"),
            "same transfer fault can be armed again immediately");
        String repeated = FaultInjectionStateV2_1.nextTransferRequest();
        require(repeated != null && repeated.endsWith("|ARRIVAL_TIMEOUT") &&
            !repeated.equals(request),
            "repeated injection uses a new request identity");
        String repeatedId = repeated.split("\\|", -1)[0];
        require(FaultInjectionStateV2_1.onTransferAcknowledgement(
            "V1|" + repeatedId + "|ACCEPTED"
        ), "second M2 acknowledgement is correlated independently");
        FaultInjectionStateV2_1.consumed("ARRIVAL_TIMEOUT");

        M2MachineStateV1.reset();
        FaultInjectionStateV2_1.reset();
        require(FaultInjectionStateV2_1.arm("DEPARTURE_TIMEOUT"),
            "arm cancellable transfer fault");
        String pending = FaultInjectionStateV2_1.nextTransferRequest();
        String pendingId = pending.split("\\|", -1)[0];
        require(M2MachineStateV1.armTransferTestFault(pending),
            "M2 stores pending transfer fault");
        require(FaultInjectionStateV2_1.onTransferAcknowledgement(
            "V1|" + pendingId + "|ACCEPTED"
        ), "M3 records M2 acceptance before cancellation");
        require(FaultInjectionStateV2_1.cancel(),
            "operator can cancel an untriggered fault");
        String cancellation = FaultInjectionStateV2_1.nextTransferRequest();
        require((pendingId + "|CANCEL").equals(cancellation),
            "cancellation retains the original request identity");
        require(M2MachineStateV1.armTransferTestFault(cancellation),
            "M2 clears only the matching pending fault");
        require(FaultInjectionStateV2_1.onTransferAcknowledgement(
            "V1|" + pendingId + "|ACCEPTED"
        ), "M3 clears the arm only after M2 confirms cancellation");
        require("-".equals(FaultInjectionStateV2_1.armedFault()),
            "cancelled transfer fault no longer blocks GUI injection");
        require(!M2MachineStateV1.armTransferTestFault(pending),
            "late original request cannot re-arm a cancelled injection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
