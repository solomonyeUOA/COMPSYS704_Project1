/** Framework-free end-to-end checks for the Member 3 Plant models. */
public final class Member3PlantSelfTest {
    private Member3PlantSelfTest() {
    }

    public static void main(String[] args) {
        testBottleThroughAllRotaryStations();
        testMultipleBottlePipeline();
        testRotaryAlignmentFault();
        testLidPlantSequence();
        System.out.println("Member3PlantSelfTest PASSED");
    }

    private static void testBottleThroughAllRotaryStations() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(table.loadBottle("B001"), "bottle loads at position 1");

        rotate(table, 0);
        require(
            table.getBottleAt(RotaryTablePlantModelV1.FILL_POSITION) != null,
            "first step moves bottle to fill position"
        );
        require(table.markFilled(), "bottle can be marked filled");

        rotate(table, 1000);
        require(table.hasBottleWaitingForLid(), "filled bottle reaches lid position");
        require(table.markLidPlaced(), "lid can be placed on filled bottle");

        rotate(table, 2000);
        require(table.markCapped(), "lidded bottle can be capped");

        rotate(table, 3000);
        BottleStateV1 completed = table.removeBottleAtExit();
        require(completed != null, "completed bottle reaches exit");
        require(completed.isFilled(), "completed bottle is filled");
        require(completed.hasLid(), "completed bottle has lid");
        require(completed.isCapped(), "completed bottle is capped");
    }

    private static void testMultipleBottlePipeline() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        require(table.loadBottle("A"), "load bottle A");
        rotate(table, 0);
        require(table.markFilled(), "fill bottle A");

        require(table.loadBottle("B"), "load bottle B while A is at P2");
        rotate(table, 1000);
        require(
            "A".equals(table.getBottleAt(2).getId()),
            "A advances to P3"
        );
        require(
            "B".equals(table.getBottleAt(1).getId()),
            "B advances to P2"
        );
        require(table.markLidPlaced(), "A receives lid while B occupies P2");
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

    private static void testRotaryAlignmentFault() {
        RotaryTablePlantModelV1 table = new RotaryTablePlantModelV1();
        table.setAlignmentFault(true);
        table.setMotorCommand(true, 0);
        require(table.tick(500), "faulted physical step still finishes moving");
        require(!table.isAligned(), "fault suppresses alignment sensor");
        table.setAlignmentFault(false);
        require(table.isAligned(), "clearing fault restores alignment sensor");
    }

    private static void rotate(RotaryTablePlantModelV1 table, long startMs) {
        table.setMotorCommand(false, startMs);
        require(table.setMotorCommand(true, startMs), "rotation starts");
        require(!table.tick(startMs + 499), "rotation takes 500 ms");
        require(table.tick(startMs + 500), "rotation completes at 500 ms");
        table.setMotorCommand(false, startMs + 500);
        require(table.isAligned(), "table is aligned after normal rotation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
