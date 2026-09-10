/** Deterministic checks for the four high-level M2 Plant models. */
public final class Member2PlantSelfTest {
    public static void main(String[] args) {
        M2PlantStateV1.reset();

        check(M2PlantStateV1.commandLoad("B301", 0L), "load command");
        M2PlantStateV1.tickLoader(99L);
        check(M2PlantStateV1.takeLoadConfirmed() == null,
            "loader does not infer early completion");
        M2PlantStateV1.tickLoader(100L);
        check("B301".equals(M2PlantStateV1.takeLoadConfirmed()),
            "loader sensor confirmation");

        check(M2PlantStateV1.registerConveyorBottle("B301"),
            "register conveyor identity");
        M2PlantStateV1.setConveyorMotor(true, 0L);
        M2PlantStateV1.tickConveyor(100L);
        check("B301|true|true|false|true|true".equals(
            M2PlantStateV1.conveyorFeedback()), "P1 arrival feedback");
        M2PlantStateV1.setConveyorMotor(false, 101L);
        check("B301|true|true|true|true|true".equals(
            M2PlantStateV1.conveyorFeedback()), "motor-stopped evidence");
        check(M2PlantStateV1.commitConveyorHandoff("B301"),
            "commit handoff");

        check(M2PlantStateV1.commandLabel("B301|LABEL_B301", 0L),
            "label command");
        M2PlantStateV1.tickLabeller(100L);
        check("B301|PASS".equals(
            M2PlantStateV1.takeLabelVerification()),
            "independent label verification");
        M2PlantStateV1.setLabelVerificationFault(true);
        check(M2PlantStateV1.commandLabel("B302|LABEL_B302", 200L),
            "second label command");
        M2PlantStateV1.tickLabeller(300L);
        check("B302|FAIL".equals(
            M2PlantStateV1.takeLabelVerification()),
            "label fault evidence");

        check(M2PlantStateV1.commandUnload("B301", 0L),
            "unload command");
        M2PlantStateV1.tickUnloader(99L);
        check(M2PlantStateV1.takeRemovalConfirmed() == null,
            "no early removal");
        M2PlantStateV1.tickUnloader(100L);
        check("B301|true".equals(
            M2PlantStateV1.takeRemovalConfirmed()),
            "removal plus empty-P6 evidence");

        System.out.println("Member2PlantSelfTest PASSED");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
