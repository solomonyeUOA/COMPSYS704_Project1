/** M3 slow-demo duration, matching deadline and prompt-reset regressions. */
public final class SlowRotaryLidTimingSelfTest {
    private static int assertions;

    private SlowRotaryLidTimingSelfTest() { }

    public static void main(String[] args) {
        rotaryPlantAndControllerAgree();
        lidPlantAndControllerAgree();
        faultsRemainBounded();
        resetIsImmediate();
        System.out.println("SlowRotaryLidTimingSelfTest PASSED (" + assertions +
            " assertions, slowdown=" + SimulationTiming.slowdown() + ")");
    }

    private static void rotaryPlantAndControllerAgree() {
        RotaryTablePlantModelV1 plant = new RotaryTablePlantModelV1();
        RotaryControllerModelV1 controller = new RotaryControllerModelV1();
        check(plant.registerContext("SLOW-ROTARY|S|200|GEOM_S|PACK_S"), "rotary context");
        check(plant.loadBottle("SLOW-ROTARY"), "rotary bottle loaded");
        check(controller.requestRotation(1L, plant.canRotate()), "rotation accepted");
        check(plant.setMotorCommand(controller.isMotorEnabled(), 1L, 0L), "motor starts");
        long duration = SimulationTiming.scaleMillis(500L);
        check(!plant.tick(duration - 1L), "rotary plant does not complete early");
        controller.tick(duration - 1L, plant.isAligned());
        check(controller.getState() == RotaryControllerModelV1.State.ROTATING,
            "rotary controller stays active throughout scaled duration");
        check(plant.tick(duration), "rotary plant completes at scaled boundary");
        controller.tick(1L, plant.isAligned());
        check(controller.getState() == RotaryControllerModelV1.State.VERIFYING_ALIGNMENT,
            "alignment evidence remains required");
        controller.tick(0L, plant.isAligned());
        check(controller.getState() == RotaryControllerModelV1.State.DONE,
            "matching alignment completes controller");
        check(plant.commitRotation(1L), "matching completion commits movement");
        check(plant.getBottleAt(1) != null && plant.getBottleAt(0) == null,
            "actual bottle advances once");
    }

    private static void lidPlantAndControllerAgree() {
        LidLoaderPlantModelV1 plant = new LidLoaderPlantModelV1(2);
        LidLoaderControllerModelV1 controller = new LidLoaderControllerModelV1();
        check(controller.requestLoad("SLOW-LID", plant.isLidAvailable()), "lid accepted");
        check(plant.setPickCommand(controller.isPickActuatorEnabled(), 0L), "pick starts");
        long pick = SimulationTiming.scaleMillis(300L);
        plant.tick(pick - 1L);
        controller.tick(pick - 1L, plant.isLidPicked(), false);
        check(!plant.isLidPicked() && controller.getState() ==
            LidLoaderControllerModelV1.State.PICKING, "pick not early or falsely timed out");
        plant.tick(pick);
        controller.tick(1L, plant.isLidPicked(), false);
        check(controller.getState() == LidLoaderControllerModelV1.State.PLACING,
            "actual pick confirmation starts placement");
        check(plant.setPlaceCommand(controller.isPlaceActuatorEnabled(), pick), "placement starts");
        long place = SimulationTiming.scaleMillis(300L);
        plant.tick(pick + place - 1L);
        controller.tick(place - 1L, plant.isLidPicked(),
            plant.isLidPlacedSensorActive(pick + place - 1L));
        check(controller.getState() == LidLoaderControllerModelV1.State.PLACING &&
            plant.getMagazineCount() == 2, "placement not early or falsely timed out");
        plant.tick(pick + place);
        controller.tick(1L, plant.isLidPicked(),
            plant.isLidPlacedSensorActive(pick + place));
        check(controller.getState() == LidLoaderControllerModelV1.State.DONE &&
            plant.getMagazineCount() == 1, "confirmed slow placement consumes one lid");
        check(plant.isLidPlacedSensorActive(pick + place + 200L) &&
            !plant.isLidPlacedSensorActive(pick + place + 201L),
            "sensor transport hold remains 200 real-time milliseconds");
    }

    private static void faultsRemainBounded() {
        RotaryControllerModelV1 rotary = new RotaryControllerModelV1();
        check(rotary.requestRotation(1L, true), "faulted rotary starts");
        rotary.tick(SimulationTiming.scaleMillis(500L), false);
        rotary.tick(SimulationTiming.scaleMillis(250L) - 1L, false);
        check(rotary.getState() == RotaryControllerModelV1.State.VERIFYING_ALIGNMENT,
            "alignment timeout is not premature");
        rotary.tick(1L, false);
        check(rotary.getState() == RotaryControllerModelV1.State.FAULT &&
            !rotary.isMotorEnabled(), "alignment fault remains bounded and motor safe");

        LidLoaderControllerModelV1 lid = new LidLoaderControllerModelV1();
        check(lid.requestLoad("SLOW-PICK-FAULT", true), "faulted pick starts");
        long timeout = SimulationTiming.scaleMillis(1000L);
        lid.tick(timeout - 1L, false, false);
        check(lid.getState() == LidLoaderControllerModelV1.State.PICKING,
            "pick timeout is not premature");
        lid.tick(1L, false, false);
        check(lid.getFault() == LidLoaderControllerModelV1.Fault.PICK_TIMEOUT &&
            !lid.isPickActuatorEnabled(), "missing pick still faults safely");

        lid = new LidLoaderControllerModelV1();
        check(lid.requestLoad("SLOW-PLACE-FAULT", true), "faulted placement starts");
        lid.tick(0L, true, false);
        lid.tick(timeout - 1L, true, false);
        check(lid.getState() == LidLoaderControllerModelV1.State.PLACING,
            "placement timeout is not premature");
        lid.tick(1L, true, false);
        check(lid.getFault() == LidLoaderControllerModelV1.Fault.PLACEMENT_TIMEOUT &&
            !lid.isPlaceActuatorEnabled(), "missing placement still faults safely");
    }

    private static void resetIsImmediate() {
        Member3MachineStateV1.reset();
        Member3PlantStateV1.reset();
        check(Member3MachineStateV1.requestRotation(true), "shared rotary active");
        check(Member3MachineStateV1.requestLidLoad("SLOW-RESET", true), "shared lid active");
        Member3PlantStateV1.setPickCommand(true);
        check(!Member3MachineStateV1.isResetSafe() && !Member3PlantStateV1.isResetSafe(),
            "active slow actions are not reset-safe before cancellation");
        check(M3SystemResetStateV1.accept("RST9002"), "M3 reset accepted");
        check(Member3MachineStateV1.isResetSafe() && Member3PlantStateV1.isResetSafe(),
            "reset immediately stops controllers and plant without awaiting duration");
        check(M3SystemResetStateV1.isQuarantined(), "reset still performs its quiet barrier");
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) { throw new AssertionError(message); }
    }
}
