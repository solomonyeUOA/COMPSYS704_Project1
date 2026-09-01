/** Framework-free checks for the Member 3 deterministic controller models. */
public final class Member3ControllerSelfTest {
    private Member3ControllerSelfTest() {
    }

    public static void main(String[] args) {
        testStatusPollingIsReadOnly();
        testRotaryHappyPathAndBarrier();
        testRotaryRecoveryEvidence();
        testLidHappyPath();
        testLidCauseSpecificRecovery();
        testSupervisorGatesMachineReset();
        System.out.println("Member3ControllerSelfTest PASSED");
    }

    private static void testStatusPollingIsReadOnly() {
        Member3MachineStateV1.reset();
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.READY, "rotary starts READY");
        require(Member3MachineStateV1.getRotaryStatus() ==
            Member3MachineStateV1.READY, "polling does not start rotation");
        require(Member3MachineStateV1.getLidStatus() ==
            Member3MachineStateV1.READY, "polling does not start lid loader");
    }

    private static void testRotaryHappyPathAndBarrier() {
        RotaryControllerModelV1 rotary = new RotaryControllerModelV1();
        require(!rotary.requestRotation(1, false),
            "an incomplete station blocks rotation");
        require(!rotary.isMotorEnabled(), "blocked rotation keeps motor off");
        require(rotary.requestRotation(1, true), "clear barrier permits step");
        require(rotary.isMotorEnabled(), "rotation enables motor");
        rotary.tick(499, false);
        require(rotary.getState() == RotaryControllerModelV1.State.ROTATING,
            "rotation lasts 500 ms");
        rotary.tick(1, false);
        require(rotary.getState() ==
            RotaryControllerModelV1.State.VERIFYING_ALIGNMENT,
            "motor stop is followed by alignment verification");
        require(!rotary.isMotorEnabled(), "verification keeps motor off");
        rotary.tick(0, true);
        require(rotary.getStatus() == Member3MachineStateV1.DONE,
            "sensor-confirmed alignment completes the step");
        require(rotary.getLastCompletedCycleId() == 1,
            "completion retains its cycle identity");
        require(rotary.acknowledgeDone(), "DONE can be acknowledged");
        require(!rotary.requestRotation(1, true),
            "a completed cycle cannot be replayed");
        require(rotary.requestRotation(2, true), "next cycle can start");
    }

    private static void testRotaryRecoveryEvidence() {
        RotaryControllerModelV1 rotary = new RotaryControllerModelV1();
        require(rotary.requestRotation(1, true), "rotation starts");
        rotary.tick(RotaryControllerModelV1.ROTATION_TIME_MS, false);
        rotary.tick(RotaryControllerModelV1.ALIGNMENT_TIMEOUT_MS, false);
        require(rotary.getStatus() == Member3MachineStateV1.FAULT,
            "missing alignment causes FAULT");
        require(!rotary.isMotorEnabled(), "fault de-energises the motor");
        require(!rotary.resetFault(new RotaryRecoveryEvidenceV1(
            true, true, false)), "one sensor is not independent evidence");
        require(rotary.resetFault(new RotaryRecoveryEvidenceV1(
            true, true, true)), "safe stop and reconciliation permit reset");
    }

    private static void testLidHappyPath() {
        LidLoaderControllerModelV1 lid = new LidLoaderControllerModelV1();
        require(lid.requestLoad("B001", true), "bottle and lid start cycle");
        require(lid.isPickActuatorEnabled(), "pick actuator starts");
        lid.tick(100, true, false);
        require(lid.getState() == LidLoaderControllerModelV1.State.PLACING,
            "pick confirmation starts placement");
        lid.tick(100, false, true);
        require(lid.getStatus() == Member3MachineStateV1.DONE,
            "placement confirmation completes cycle");
        require("B001".equals(lid.takeCompletedBottleId()),
            "completion preserves bottle identity");
        require(lid.takeCompletedBottleId() == null,
            "completion is published once");
        require(lid.acknowledgeDone(), "lid DONE can be acknowledged");
    }

    private static void testLidCauseSpecificRecovery() {
        LidLoaderControllerModelV1 lid = new LidLoaderControllerModelV1();
        require(!lid.requestLoad("B001", false), "empty magazine rejects cycle");
        require(lid.getFault() == LidLoaderControllerModelV1.Fault.MAGAZINE_EMPTY,
            "empty magazine cause is retained");
        require(lid.resetFault(new LidRecoveryEvidenceV1(
            true, false, false, false, false)),
            "resource restoration clears magazine-empty fault");
        require(lid.requestLoad("B001", true), "pick cycle starts");
        lid.tick(LidLoaderControllerModelV1.PICK_TIMEOUT_MS, false, false);
        require(!lid.resetFault(new LidRecoveryEvidenceV1(
            true, true, false, false, false)),
            "pick timeout requires proof that no lid is held");
        require(lid.resetFault(new LidRecoveryEvidenceV1(
            true, true, true, false, false)),
            "home, resource and no-lid evidence clear pick fault");
        lid = new LidLoaderControllerModelV1();
        require(lid.requestLoad("B002", true), "placement cycle starts");
        lid.tick(0, true, false);
        lid.tick(LidLoaderControllerModelV1.PLACE_TIMEOUT_MS, false, false);
        require(!lid.resetFault(new LidRecoveryEvidenceV1(
            true, true, true, false, true)),
            "placement timeout requires bottle/lid reconciliation");
        require(lid.resetFault(new LidRecoveryEvidenceV1(
            true, true, false, true, true)),
            "reconciled placement and healthy sensor permit reset");
    }

    private static void testSupervisorGatesMachineReset() {
        Member3MachineStateV1.reset();
        require(Member3MachineStateV1.requestRotation(true),
            "shared rotary cycle starts");
        Member3MachineStateV1.tickRotary(
            RotaryControllerModelV1.ROTATION_TIME_MS,
            false
        );
        Member3MachineStateV1.tickRotary(
            RotaryControllerModelV1.ALIGNMENT_TIMEOUT_MS,
            false
        );
        require(FaultSupervisorStateV2_1.localSummary().contains(
            "NO_AUTO_REHOME"), "supervisor observes rotary fault");
        require(!Member3MachineStateV1.resetRotaryFault(
            new RotaryRecoveryEvidenceV1(true, true, false)),
            "supervisor rejects incomplete rotary evidence");
        require(Member3MachineStateV1.resetRotaryFault(
            new RotaryRecoveryEvidenceV1(true, true, true)),
            "supervisor authorizes reconciled rotary reset");

        require(!Member3MachineStateV1.requestLidLoad("B003", false),
            "shared lid controller detects missing resource");
        require(FaultSupervisorStateV2_1.localSummary().contains(
            "WAIT_RESOURCE"), "supervisor classifies magazine-empty fault");
        require(Member3MachineStateV1.resetLidFault(
            new LidRecoveryEvidenceV1(true, false, false, false, false)),
            "resource evidence authorizes lid reset");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
