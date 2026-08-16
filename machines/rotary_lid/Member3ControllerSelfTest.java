/** Framework-free checks for the Member 3 deterministic controller models. */
public final class Member3ControllerSelfTest {
    private Member3ControllerSelfTest() {
    }

    public static void main(String[] args) {
        testStatusPollingIsReadOnly();
        testRotaryHappyPathAndInterlock();
        testRotaryAlignmentFault();
        testLidHappyPath();
        testLidFaults();
        System.out.println("Member3ControllerSelfTest PASSED");
    }

    private static void testStatusPollingIsReadOnly() {
        Member3MachineStateV1.reset();
        require(
            Member3MachineStateV1.getTransportStatus() ==
                Member3MachineStateV1.READY,
            "rotary starts READY"
        );
        require(
            Member3MachineStateV1.getTransportStatus() ==
                Member3MachineStateV1.READY,
            "polling must not advance rotary"
        );
        require(
            Member3MachineStateV1.getLidStatus() ==
                Member3MachineStateV1.READY,
            "polling must not advance lid loader"
        );
    }

    private static void testRotaryHappyPathAndInterlock() {
        RotaryControllerModelV1 rotary = new RotaryControllerModelV1();
        require(!rotary.requestRotation(true), "capped bottle blocks rotation");
        require(!rotary.isMotorEnabled(), "blocked rotation keeps motor off");
        require(rotary.requestRotation(false), "clear table permits rotation");
        require(rotary.isMotorEnabled(), "rotation enables motor");

        rotary.tick(499, false);
        require(
            rotary.getState() == RotaryControllerModelV1.State.ROTATING,
            "rotation lasts 500 ms"
        );
        rotary.tick(1, false);
        require(
            rotary.getState() ==
                RotaryControllerModelV1.State.VERIFYING_ALIGNMENT,
            "motor stop is followed by alignment verification"
        );
        require(!rotary.isMotorEnabled(), "verification keeps motor off");
        rotary.tick(0, true);
        require(
            rotary.getStatus() == Member3MachineStateV1.DONE,
            "aligned step completes"
        );
        require(rotary.getTablePosition() == 1, "table advances one of six positions");
        require(rotary.acknowledgeDone(), "DONE can be acknowledged");
        require(
            rotary.getStatus() == Member3MachineStateV1.READY,
            "acknowledgement restores READY"
        );
    }

    private static void testRotaryAlignmentFault() {
        RotaryControllerModelV1 rotary = new RotaryControllerModelV1();
        require(rotary.requestRotation(false), "rotation starts");
        rotary.tick(RotaryControllerModelV1.ROTATION_TIME_MS, false);
        rotary.tick(RotaryControllerModelV1.ALIGNMENT_TIMEOUT_MS, false);
        require(
            rotary.getStatus() == Member3MachineStateV1.FAULT,
            "missing alignment causes FAULT"
        );
        require(!rotary.resetFault(false), "unaligned table cannot reset");
        require(rotary.resetFault(true), "aligned table can reset");
    }

    private static void testLidHappyPath() {
        LidLoaderControllerModelV1 lid = new LidLoaderControllerModelV1();
        require(!lid.requestLoad(false, true), "no bottle means no cycle");
        require(lid.requestLoad(true, true), "bottle and lid start cycle");
        require(lid.isPickActuatorEnabled(), "pick actuator starts");

        lid.tick(100, true, false);
        require(
            lid.getState() == LidLoaderControllerModelV1.State.PLACING,
            "pick confirmation starts placement"
        );
        require(lid.isPlaceActuatorEnabled(), "place actuator starts");
        lid.tick(100, false, true);
        require(
            lid.getStatus() == Member3MachineStateV1.DONE,
            "placement confirmation completes cycle"
        );
        require(
            !lid.requestLoad(true, true),
            "DONE blocks duplicate lid placement before acknowledgement"
        );
        require(lid.acknowledgeDone(), "lid DONE can be acknowledged");
    }

    private static void testLidFaults() {
        LidLoaderControllerModelV1 lid = new LidLoaderControllerModelV1();
        require(!lid.requestLoad(true, false), "empty magazine rejects cycle");
        require(
            lid.getStatus() == Member3MachineStateV1.FAULT,
            "empty magazine causes FAULT"
        );
        require(!lid.resetFault(false), "empty magazine cannot reset");
        require(lid.resetFault(true), "replenished magazine can reset");

        require(lid.requestLoad(true, true), "cycle restarts after reset");
        lid.tick(LidLoaderControllerModelV1.PICK_TIMEOUT_MS, false, false);
        require(
            lid.getStatus() == Member3MachineStateV1.FAULT,
            "missing pick confirmation causes FAULT"
        );
        require(!lid.isPickActuatorEnabled(), "fault de-energises actuator");

        lid = new LidLoaderControllerModelV1();
        require(lid.requestLoad(true, true), "placement-timeout cycle starts");
        lid.tick(0, true, false);
        lid.tick(LidLoaderControllerModelV1.PLACE_TIMEOUT_MS, false, false);
        require(
            lid.getStatus() == Member3MachineStateV1.FAULT,
            "missing placement confirmation causes FAULT"
        );
        require(!lid.isPlaceActuatorEnabled(), "fault de-energises placer");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
