/** Framework-free end-to-end checks for the Member 3 Plant models. */
public final class Member3PlantSelfTest {
    private Member3PlantSelfTest() {
    }

    public static void main(String[] args) {
        testBottleThroughSixPositions();
        testMultipleBottlePipeline();
        testIdentityAndP6Interlocks();
        testRotaryAlignmentFault();
        testLidPlantSequence();
        System.out.println("Member3PlantSelfTest PASSED");
    }

    private static void testBottleThroughSixPositions() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(table.registerContext(context("B001")), "context accepted");
        require(table.loadBottle("B001"), "bottle loads at P1");
        rotate(table, 1, 0);
        require("B001".equals(table.getBottleAt(1).getId()),
            "first step moves bottle to P2 fill");
        require(context("B001").equals(table.takeFillOffer()),
            "P2 sends full bottle context once");
        require(table.takeFillOffer() == null, "fill offer is not duplicated");
        require(table.markFilled("B001"), "matching fill result accepted");
        rotate(table, 2, 1000);
        require("B001".equals(table.getBottleWaitingForLidId()),
            "filled bottle reaches P3 lid");
        require(table.markLidPlaced("B001"), "matching lid result accepted");
        rotate(table, 3, 2000);
        require(context("B001").equals(table.takeCapOffer()),
            "P4 sends full bottle context once");
        require(table.markCapped("B001"), "matching cap result accepted");
        rotate(table, 4, 3000);
        require("B001".equals(table.getBottleAt(4).getId()),
            "bottle passes through P5 transfer");
        rotate(table, 5, 4000);
        require("B001".equals(table.takeLabelOffer()),
            "P6 offers the capped bottle to labelling");
        require(table.markLabelled("B001"), "matching label result accepted");
        require(!table.clearP6("WRONG"), "wrong clear identity is rejected");
        require(table.clearP6("B001"),
            "matching labelled bottle can be physically cleared");
    }

    private static void testMultipleBottlePipeline() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(table.registerContext(context("A")), "context A accepted");
        require(table.registerContext(context("B")), "context B accepted");
        require(table.loadBottle("A"), "load A");
        rotate(table, 1, 0);
        require(table.markFilled("A"), "fill A");
        require(table.loadBottle("B"), "load B while A occupies P2");
        rotate(table, 2, 1000);
        require("A".equals(table.getBottleAt(2).getId()), "A advances to P3");
        require("B".equals(table.getBottleAt(1).getId()), "B advances to P2");
        require(table.markLidPlaced("A"), "A receives lid");
        require(table.markFilled("B"), "B fills independently");
    }

    private static void testIdentityAndP6Interlocks() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(!table.loadBottle("bad id"), "invalid bottle ID is rejected");
        require(table.loadBottle("NOCTX"), "P1 accepts frozen bottle identity");
        require(!table.canRotate(), "missing full context blocks P1 release");
        require(table.registerContext(context("NOCTX")),
            "matching P1 context completes the barrier");
        require(table.canRotate(), "context-confirmed P1 may rotate");
        table = new RotaryTablePlantModelV1();
        require(table.registerContext(context("C")), "context C accepted");
        require(table.loadBottle("C"), "load C");
        rotate(table, 1, 0);
        require(!table.markFilled("D"), "stale completion is rejected");
        require(table.markFilled("C"), "matching completion accepted");
        rotate(table, 2, 1000);
        require(table.markLidPlaced("C"), "lid C");
        rotate(table, 3, 2000);
        require(table.markCapped("C"), "cap C");
        rotate(table, 4, 3000);
        rotate(table, 5, 4000);
        require(!table.canRotate(), "occupied P6 blocks the next indexed step");
        require(!table.clearP6("C"), "unlabelled P6 bottle cannot be cleared");
    }

    private static void testRotaryAlignmentFault() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(table.registerContext(context("D")), "context D accepted");
        require(table.loadBottle("D"), "load D");
        table.setAlignmentFault(true);
        table.setMotorCommand(false, 1, 0);
        require(table.setMotorCommand(true, 1, 0), "physical movement starts");
        require(table.tick(500), "physical step reaches sensor check");
        require(!table.isAligned(), "fault suppresses alignment confirmation");
        require(!table.commitRotation(1), "unaligned movement cannot commit");
        require(table.getBottleAt(0) != null,
            "slots do not shift before confirmed commit");
    }

    private static void testLidPlantSequence() {
        LidLoaderPlantModelV1 lid = new LidLoaderPlantModelV1();
        require(lid.getMagazineCount() == 5, "magazine starts with five lids");
        require(lid.setPickCommand(true, 0), "pick starts on rising command");
        lid.tick(299);
        require(!lid.isLidPicked(), "pick is not early");
        lid.tick(300);
        require(lid.isLidPicked(), "pick completes after 300 ms");
        lid.setPickCommand(false, 300);
        require(lid.setPlaceCommand(true, 300), "place starts after pick");
        lid.tick(600);
        require(lid.isLidPlacedSensorActive(600), "placement sensor activates");
        require(lid.getMagazineCount() == 4, "one lid is consumed");
    }

    private static void rotate(
        RotaryTablePlantModelV1 table,
        long cycleId,
        long startMs
    ) {
        table.setMotorCommand(false, cycleId, startMs);
        require(table.setMotorCommand(true, cycleId, startMs),
            "rotation starts for cycle " + cycleId);
        require(!table.tick(startMs + 499), "rotation takes 500 ms");
        require(table.tick(startMs + 500), "physical movement completes");
        table.setMotorCommand(false, cycleId, startMs + 500);
        require(table.isAligned(), "normal movement is aligned");
        int completedBeforeCommit = table.getCompletedSteps();
        require(table.commitRotation(cycleId), "matching DONE commits slots");
        require(table.getCompletedSteps() == completedBeforeCommit + 1,
            "exactly one atomic slot update is committed");
    }

    private static String context(String bottleId) {
        return bottleId + "|S|200|GEOM_S|PACK_S";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
