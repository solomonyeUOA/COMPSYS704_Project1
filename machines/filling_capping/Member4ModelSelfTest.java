/** Framework-free deterministic checks for the complete M4 model. */
public final class Member4ModelSelfTest {
    private Member4ModelSelfTest() {
    }

    public static void main(String[] args) {
        testContextRegistry();
        testSmallAndLargeFilling();
        testCalibrationAndCumulativeOverflow();
        testFillerGatingAndFaults();
        testCapperProfilesAndInterlocks();
        testSortPackRoutingAndPackages();
        System.out.println("Member4ModelSelfTest PASSED");
    }

    private static void testContextRegistry() {
        BottleContextRegistryModelV1 registry =
            new BottleContextRegistryModelV1();
        require(
            "B001|S|200|GEOM_S|PACK_S".equals(
                registry.acceptRecognition("B001|S|200")
            ),
            "small recognition creates canonical context"
        );
        require(
            registry.acceptRecognition("B001|S|200") == null,
            "identical recognition duplicate is a no-op"
        );
        boolean rejected = false;
        try {
            registry.acceptRecognition("B002|S|500");
        }
        catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "size/capacity mismatch is rejected");
        require(registry.getStatus() == M4StatusV1.FAULT,
            "invalid context reports FAULT");
    }

    private static void testSmallAndLargeFilling() {
        String small = "S001|S|200|GEOM_S|PACK_S";
        String large = "L001|L|500|GEOM_L|PACK_L";
        String smallADone = runFillerA(small, 60, 120);
        require(smallADone.endsWith("|120"), "small A target is 120 mL");
        String smallFilled = runFillerB(smallADone, 40, 80);
        require("S001".equals(smallFilled), "small B target completes");

        String largeADone = runFillerA(large, 60, 300);
        require(largeADone.endsWith("|300"), "large A target is 300 mL");
        String largeFilled = runFillerB(largeADone, 40, 200);
        require("L001".equals(largeFilled), "large B target completes");
    }

    private static void testFillerGatingAndFaults() {
        FillerControllerModelV1 fillerB = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, 0, 100
        );
        fillerB.setRatio(40);
        require(fillerB.getStatus() == M4StatusV1.READY,
            "recipe alone does not start Filler B");
        fillerB.tick(1000);
        require(fillerB.getStatus() == M4StatusV1.READY,
            "status observation does not advance Filler B");

        require(!fillerB.acceptFillADone(
            "BAD|S|200|GEOM_S|PACK_S|100", 0
        ), "complement mismatch is rejected");
        require(fillerB.getStatus() == M4StatusV1.FAULT,
            "complement mismatch faults before a valve command");

        FillerControllerModelV1 fillerA = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_A, 0, 1000
        );
        FillerPlantModelV1 plant = new FillerPlantModelV1(10, 10, 10);
        fillerA.setRatio(60);
        plant.setForcedOverflowMl(1);
        fillerA.acceptBottleAtFill("OV1|S|200|GEOM_S|PACK_S", 0);
        pumpFiller(fillerA, plant, 0, 100);
        require(fillerA.getStatus() == M4StatusV1.FAULT,
            "out-of-tolerance/overflow volume enters FAULT");
        require(!plant.isInjectorOpen() && !plant.isInletOpen(),
            "fault de-energises both valves");
        require(fillerA.takeCompletion() == null,
            "fault suppresses FILL_A_DONE");

        FillerControllerModelV1 timeoutController =
            new FillerControllerModelV1(
                FillerControllerModelV1.LIQUID_A, 0, 50
            );
        FillerPlantModelV1 timeoutPlant = new FillerPlantModelV1(0, 0, 0);
        timeoutController.setRatio(60);
        timeoutPlant.setForceDoseTimeout(true);
        timeoutController.acceptBottleAtFill(
            "TO1|S|200|GEOM_S|PACK_S", 0
        );
        pumpFiller(timeoutController, timeoutPlant, 0, 100);
        require(timeoutController.getStatus() == M4StatusV1.FAULT,
            "missing dose feedback enters TIMEOUT fault");
        require(!timeoutPlant.isInjectorOpen() && !timeoutPlant.isInletOpen(),
            "timeout SAFE_STOP closes both valves");

        FillerControllerModelV1 conflictController =
            new FillerControllerModelV1(
                FillerControllerModelV1.LIQUID_A, 0, 1000
            );
        FillerPlantModelV1 conflictPlant = new FillerPlantModelV1(0, 0, 0);
        conflictController.setRatio(60);
        conflictPlant.setForceSensorConflict(true);
        conflictController.acceptBottleAtFill(
            "SC1|S|200|GEOM_S|PACK_S", 0
        );
        pumpFiller(conflictController, conflictPlant, 0, 20);
        require(conflictController.getStatus() == M4StatusV1.FAULT,
            "contradictory Filler sensor state enters FAULT");
        require(conflictController.takeCompletion() == null,
            "sensor conflict emits no completion");
    }

    private static void testCalibrationAndCumulativeOverflow() {
        FillerPlantModelV1 calibrated = new FillerPlantModelV1(0, 0, 0, 5);
        require(calibrated.acceptCommand(
            "CAL1|SET_GEOMETRY|GEOM_S", 0
        ), "calibration Plant accepts geometry");
        calibrated.tick(0);
        calibrated.takeFeedback();
        require(calibrated.acceptCommand(
            "CAL1|START_DOSE|120", 0
        ), "calibration Plant accepts target");
        require(calibrated.getCommandedShutoffMl() == 115,
            "5 mL lead closes the injector at 115 mL");
        calibrated.tick(0);
        require(calibrated.getMeasuredMl() == 120,
            "calibrated lead reaches the 120 mL final target");

        FillerControllerModelV1 fillerB = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, 1, 0, 1000
        );
        FillerPlantModelV1 overflowing = new FillerPlantModelV1(0, 0, 0);
        fillerB.setRatio(40);
        require(fillerB.acceptFillADone(
            "OV2|S|200|GEOM_S|PACK_S|120", 0
        ), "Filler B accepts matching measured A");
        overflowing.setForcedOverflowMl(1);
        pumpFiller(fillerB, overflowing, 0, 20);
        require(fillerB.getStatus() == M4StatusV1.FAULT,
            "cumulative 201 mL is overflow even within local tolerance");
        require(fillerB.takeCompletion() == null,
            "cumulative overflow suppresses MARK_FILLED");
    }

    private static void testCapperProfilesAndInterlocks() {
        CapperControllerModelV1 controller = new CapperControllerModelV1(1000);
        CapperPlantModelV1 plant = new CapperPlantModelV1(10);
        require(controller.acceptBottleAtCap(
            "CAP-L|L|500|GEOM_L|PACK_L", 0
        ), "large bottle accepted by Capper");
        for (long now = 0; now <= 300; now += 10) {
            transferCapper(controller, plant, now);
        }
        require("CAP-L".equals(controller.takeCompletion()),
            "Capper emits one correlated completion");
        require("GEOM_L".equals(plant.getGeometryProfile()),
            "Capper selects the large geometry profile");
        require(!plant.isClamped() && !plant.isLowered(),
            "Capper ends raised and unclamped");
        require(!controller.acceptBottleAtCap(
            "CAP-L|L|500|GEOM_L|PACK_L", 400
        ), "completed duplicate does not restart capping");
        require(controller.takePlantCommand() == null,
            "duplicate creates no Plant action");

        CapperControllerModelV1 faulty = new CapperControllerModelV1(1000);
        CapperPlantModelV1 jammed = new CapperPlantModelV1(0);
        jammed.setForcedFaultAction("TWIST");
        faulty.acceptBottleAtCap("CAP-F|S|200|GEOM_S|PACK_S", 0);
        for (long now = 0; now <= 20; now++) {
            transferCapper(faulty, jammed, now);
        }
        require(faulty.getStatus() == M4StatusV1.FAULT,
            "Capper actuator fault enters FAULT");
        require(faulty.takeCompletion() == null,
            "Capper fault suppresses MARK_CAPPED");
    }

    private static void testSortPackRoutingAndPackages() {
        SortPackControllerModelV1 controller =
            new SortPackControllerModelV1(2, 2, 1000);
        SortPackPlantModelV1 plant = new SortPackPlantModelV1(10, 10);
        runSortPack(controller, plant, "SP1|S|200|GEOM_S|PACK_S", 0);
        runSortPack(controller, plant, "SP2|S|200|GEOM_S|PACK_S", 100);
        require(controller.getSmallBottleCount() == 2,
            "two small bottles are placed");
        require(controller.getSmallPackageCount() == 1,
            "configured small package closes after two bottles");
        require("LANE_S".equals(plant.getLane()),
            "small bottles select LANE_S");

        SortPackControllerModelV1 faulty =
            new SortPackControllerModelV1(2, 2, 1000);
        SortPackPlantModelV1 wrongLane = new SortPackPlantModelV1(10, 10);
        wrongLane.setForceWrongLane(true);
        faulty.acceptBottleReady("SP3|L|500|GEOM_L|PACK_L", 0);
        for (long now = 0; now <= 50; now += 10) {
            transferSortPack(faulty, wrongLane, now);
        }
        require(faulty.getStatus() == M4StatusV1.FAULT,
            "wrong-lane feedback enters FAULT");
        require(faulty.getLargeBottleCount() == 0,
            "wrong lane is never counted");

        SortPackControllerModelV1 missingPackage =
            new SortPackControllerModelV1(2, 2, 1000);
        SortPackPlantModelV1 unavailable = new SortPackPlantModelV1(0, 0);
        unavailable.setPackagePresent(false);
        missingPackage.acceptBottleReady(
            "SP4|S|200|GEOM_S|PACK_S", 0
        );
        for (long now = 0; now <= 20; now++) {
            transferSortPack(missingPackage, unavailable, now);
        }
        require(missingPackage.getStatus() == M4StatusV1.FAULT,
            "missing package enters FAULT");
        require(missingPackage.getSmallBottleCount() == 0,
            "missing package is never counted");
    }

    private static String runFillerA(
        String context,
        int ratio,
        int expectedTarget
    ) {
        FillerControllerModelV1 controller = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_A, 0, 1000
        );
        FillerPlantModelV1 plant = new FillerPlantModelV1(10, 10, 10);
        controller.setRatio(ratio);
        require(controller.acceptBottleAtFill(context, 0),
            "Filler A accepts context");
        pumpFiller(controller, plant, 0, 100);
        require(controller.getTargetMl() == expectedTarget,
            "Filler A calculated expected target");
        require(controller.getStatus() == M4StatusV1.DONE,
            "Filler A reaches DONE");
        require(!plant.isInjectorOpen() && !plant.isInletOpen(),
            "Filler A completion is physically safe");
        return controller.takeCompletion();
    }

    private static String runFillerB(
        String fillADone,
        int ratio,
        int expectedTarget
    ) {
        FillerControllerModelV1 controller = new FillerControllerModelV1(
            FillerControllerModelV1.LIQUID_B, 0, 1000
        );
        FillerPlantModelV1 plant = new FillerPlantModelV1(10, 10, 10);
        controller.setRatio(ratio);
        require(controller.acceptFillADone(fillADone, 0),
            "Filler B accepts matching safe A completion");
        pumpFiller(controller, plant, 0, 100);
        require(controller.getTargetMl() == expectedTarget,
            "Filler B calculated expected target");
        require(controller.getStatus() == M4StatusV1.DONE,
            "Filler B reaches DONE");
        return controller.takeCompletion();
    }

    private static void pumpFiller(
        FillerControllerModelV1 controller,
        FillerPlantModelV1 plant,
        long start,
        long end
    ) {
        for (long now = start; now <= end; now += 10) {
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

    private static void transferCapper(
        CapperControllerModelV1 controller,
        CapperPlantModelV1 plant,
        long now
    ) {
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

    private static void runSortPack(
        SortPackControllerModelV1 controller,
        SortPackPlantModelV1 plant,
        String context,
        long start
    ) {
        require(controller.acceptBottleReady(context, start),
            "SortPack accepts a new context");
        for (long now = start; now <= start + 80; now += 10) {
            transferSortPack(controller, plant, now);
        }
        require(controller.takeCompletion() != null,
            "sensor-confirmed placement records internal completion");
    }

    private static void transferSortPack(
        SortPackControllerModelV1 controller,
        SortPackPlantModelV1 plant,
        long now
    ) {
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
