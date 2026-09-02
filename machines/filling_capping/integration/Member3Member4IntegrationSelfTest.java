/** Deterministic proof of the frozen M3/M4 P2 and P4 model boundaries. */
public final class Member3Member4IntegrationSelfTest {
    private Member3Member4IntegrationSelfTest() {
    }

    public static void main(String[] args) {
        testBottle("I-S", "S", 200, "GEOM_S", "PACK_S", 120, 80);
        testBottle("I-L", "L", 500, "GEOM_L", "PACK_L", 300, 200);
        System.out.println("Member3Member4IntegrationSelfTest PASSED");
    }

    private static void testBottle(
        String bottleId,
        String size,
        int capacity,
        String geometry,
        String packaging,
        int expectedA,
        int expectedB
    ) {
        String context = bottleId + "|" + size + "|" + capacity + "|" +
            geometry + "|" + packaging;
        RotaryTablePlantModelV1 rotary = new RotaryTablePlantModelV1();
        require(rotary.registerContext(context),
            "M3 accepts the canonical M4 context");
        require(rotary.loadBottle(bottleId), "M3 loads matching bottleId");

        rotate(rotary, 1, 0);
        String atFill = rotary.takeFillOffer();
        require(context.equals(atFill),
            "M3 forwards unchanged full context at P2");
        String filledId = runFill(atFill, expectedA, expectedB);
        require(rotary.markFilled(filledId),
            "M3 accepts M4 MARK_FILLED for the P2 bottle");

        rotate(rotary, 2, 1000);
        require(bottleId.equals(rotary.getBottleWaitingForLidId()),
            "same bottle reaches P3");
        require(rotary.markLidPlaced(bottleId),
            "test fixture confirms the M3 lid cycle");

        rotate(rotary, 3, 2000);
        String atCap = rotary.takeCapOffer();
        require(context.equals(atCap),
            "M3 forwards unchanged full context at P4");
        String cappedId = runCapper(atCap, geometry);
        require(rotary.markCapped(cappedId),
            "M3 accepts M4 MARK_CAPPED for the P4 bottle");
    }

    private static String runFill(
        String context,
        int expectedA,
        int expectedB
    ) {
        FillerControllerModelV1 fillerA = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_A, 0, 1000
        );
        FillerControllerModelV1 fillerB = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, 0, 1000
        );
        FillerPlantModelV1 plantA = new FillerPlantModelV1(0, 0, 0);
        FillerPlantModelV1 plantB = new FillerPlantModelV1(0, 0, 0);
        fillerA.setRatio(60);
        fillerB.setRatio(40);
        require(fillerA.acceptBottleAtFill(context, 0),
            "M4 Filler A accepts the P2 context");
        pumpFiller(fillerA, plantA);
        require(fillerA.getTargetMl() == expectedA,
            "M4 Filler A uses capacity-aware target");
        String fillADone = fillerA.takeCompletion();
        require(fillADone != null && fillerB.acceptFillADone(fillADone, 100),
            "M4 Filler B accepts matching safe A completion");
        pumpFiller(fillerB, plantB);
        require(fillerB.getTargetMl() == expectedB,
            "M4 Filler B uses capacity-aware target");
        return fillerB.takeCompletion();
    }

    private static String runCapper(String context, String geometry) {
        CapperControllerModelV1 controller = new CapperControllerModelV1(1000);
        CapperPlantModelV1 plant = new CapperPlantModelV1(0);
        require(controller.acceptBottleAtCap(context, 0),
            "M4 Capper accepts the P4 context");
        for (long now = 0; now < 30; now++) {
            String command;
            while ((command = controller.takePlantCommand()) != null) {
                plant.acceptCommand(command, now);
            }
            plant.tick(now);
            String feedback;
            while ((feedback = plant.takeFeedback()) != null) {
                controller.acceptPlantFeedback(feedback, now);
            }
            controller.tick(now);
        }
        require(geometry.equals(plant.getGeometryProfile()),
            "M4 Capper selects the matching S/L geometry");
        return controller.takeCompletion();
    }

    private static void pumpFiller(
        FillerControllerModelV1 controller,
        FillerPlantModelV1 plant
    ) {
        for (long now = 0; now < 20; now++) {
            String command;
            while ((command = controller.takePlantCommand()) != null) {
                plant.acceptCommand(command, now);
            }
            plant.tick(now);
            String feedback;
            while ((feedback = plant.takeFeedback()) != null) {
                controller.acceptPlantFeedback(feedback, now);
            }
            controller.tick(now);
        }
    }

    private static void rotate(
        RotaryTablePlantModelV1 rotary,
        long cycleId,
        long now
    ) {
        rotary.setMotorCommand(false, cycleId, now);
        require(rotary.setMotorCommand(true, cycleId, now),
            "M3 starts the next allowed rotation");
        require(rotary.tick(now + RotaryControllerModelV1.ROTATION_TIME_MS),
            "M3 Plant completes the physical step");
        require(rotary.commitRotation(cycleId),
            "M3 commits the aligned rotation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
