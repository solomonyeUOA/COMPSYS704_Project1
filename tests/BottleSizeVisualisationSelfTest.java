/** Presentation-only regression for S/L bottle geometry and work heights. */
public final class BottleSizeVisualisationSelfTest {
    private static int assertions;

    private BottleSizeVisualisationSelfTest() { }

    public static void main(String[] args) {
        BottleVisualGeometry small =
            BottleVisualGeometry.forSizeCode("S");
        BottleVisualGeometry large =
            BottleVisualGeometry.forSizeCode("L");
        BottleVisualGeometry unknown =
            BottleVisualGeometry.forSizeCode("--");

        require(small.getVisualScale() == 0.82,
            "small visual scale remains the approved value");
        require(large.getVisualScale() == 1.18,
            "large visual scale remains the approved value");
        require(small.totalHeight(130) < large.totalHeight(130),
            "S total height is smaller than L total height");
        require(small.bodyWidth(70) < large.bodyWidth(70),
            "S bottle silhouette is narrower than L");
        require("Small (S) - 200 mL".equals(small.getDisplayLabel()),
            "small profile has truthful capacity label");
        require("Large (L) - 500 mL".equals(large.getDisplayLabel()),
            "large profile has truthful capacity label");
        require(!unknown.isKnown() && "--".equals(unknown.getDisplayLabel()),
            "unknown geometry is labelled as unknown");

        double baseY = 400.0;
        require(small.topY(baseY, 130) + small.totalHeight(130) == baseY,
            "small bottle is scaled from its base");
        require(large.topY(baseY, 130) + large.totalHeight(130) == baseY,
            "large bottle is scaled from its base");

        require(small.workingTopY(baseY, 130, 5.0) >
                large.workingTopY(baseY, 130, 5.0),
            "filler works lower for S than L");
        require(small.workingTopY(baseY, 122, small.capHeight(122)) >
                large.workingTopY(baseY, 122, large.capHeight(122)),
            "lid mechanism works lower for S than L");
        require(small.workingTopY(baseY, 132, 43.0) >
                large.workingTopY(baseY, 132, 43.0),
            "capper head works lower for S than L");
        require(small.labelCenterY(370.0, 125) >
                large.labelCenterY(370.0, 125),
            "labeller applicator works lower for S than L");

        double interpolated = BottleVisualGeometry.interpolateWorkingY(
            small.workingTopY(baseY, 130, 5.0),
            large.workingTopY(baseY, 130, 5.0)
        );
        require(interpolated < small.workingTopY(baseY, 130, 5.0) &&
                interpolated > large.workingTopY(baseY, 130, 5.0),
            "machine height interpolates toward the new profile");
        require(Math.abs(interpolated -
                small.workingTopY(baseY, 130, 5.0)) <= 8.0,
            "machine height interpolation is bounded");

        exerciseSizeSwitches();

        System.out.println("S_GEOMETRY_SMALLER_THAN_L = PASS");
        System.out.println("S_BOTTLE_BASE_ANCHORED = PASS");
        System.out.println("L_BOTTLE_BASE_ANCHORED = PASS");
        System.out.println("FILLER_S_TARGET_LOWER_THAN_L = PASS");
        System.out.println("LID_S_TARGET_LOWER_THAN_L = PASS");
        System.out.println("CAPPER_S_TARGET_LOWER_THAN_L = PASS");
        System.out.println("LABELLER_S_TARGET_LOWER_THAN_L = PASS");
        System.out.println("SIZE_SWITCH_S_TO_L = PASS");
        System.out.println("SIZE_SWITCH_L_TO_S = PASS");
        System.out.println("RESET_CLEARS_STALE_SIZE = PASS");
        System.out.println("BottleSizeVisualisationSelfTest PASSED assertions=" +
            assertions);
    }

    private static void exerciseSizeSwitches() {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        ABSVisualisationTeamIpModel team = new ABSVisualisationTeamIpModel();
        ABSVisualisationPresentationModel presentation =
            new ABSVisualisationPresentationModel();
        ABSLiveTwinModel twin = new ABSLiveTwinModel();
        int[] statuses = new int[ABSVisualisationFlowModel.MODULE_COUNT];
        boolean[] received =
            new boolean[ABSVisualisationFlowModel.MODULE_COUNT];
        flow.acceptRequired(1);

        require(twin.accept(snapshot("9100", 1, "ORDER-S-B001", "S", 200)),
            "S-order Twin snapshot accepted");
        ABSVisualisationPresentationModel.Snapshot small = presentation.publish(
            flow.getSnapshot(), twin.snapshot(), team.getSnapshot(), statuses,
            received
        );
        require("S".equals(small.getBottleSize(
                ABSVisualisationFlowModel.LOADER, 1).getSizeCode()),
            "first order selects small geometry");

        require(twin.accept(snapshot("9100", 2, "ORDER-L-B001", "L", 500)),
            "following L-order Twin snapshot accepted");
        ABSVisualisationPresentationModel.Snapshot large = presentation.publish(
            flow.getSnapshot(), twin.snapshot(), team.getSnapshot(), statuses,
            received
        );
        require("L".equals(large.getBottleSize(
                ABSVisualisationFlowModel.LOADER, 1).getSizeCode()),
            "S to L order switch replaces bottle geometry");

        ABSVisualisationPresentationModel reversePresentation =
            new ABSVisualisationPresentationModel();
        ABSLiveTwinModel reverseTwin = new ABSLiveTwinModel();
        require(reverseTwin.accept(snapshot(
                "9200", 1, "ORDER-L2-B001", "L", 500)),
            "reverse L-order Twin snapshot accepted");
        ABSVisualisationPresentationModel.Snapshot reverseLarge =
            reversePresentation.publish(
                flow.getSnapshot(), reverseTwin.snapshot(), team.getSnapshot(),
                statuses, received
            );
        require("L".equals(reverseLarge.getBottleSize(
                ABSVisualisationFlowModel.CAPPER, 1).getSizeCode()),
            "reverse case starts with large geometry");
        require(reverseTwin.accept(snapshot(
                "9200", 2, "ORDER-S2-B001", "S", 200)),
            "following S-order Twin snapshot accepted");
        ABSVisualisationPresentationModel.Snapshot reverseSmall =
            reversePresentation.publish(
                flow.getSnapshot(), reverseTwin.snapshot(), team.getSnapshot(),
                statuses, received
            );
        require("S".equals(reverseSmall.getBottleSize(
                ABSVisualisationFlowModel.CAPPER, 1).getSizeCode()),
            "L to S order switch replaces bottle geometry");

        reverseTwin.observeReset("RST9200");
        reversePresentation.reset("RST9200");
        ABSVisualisationPresentationModel.Snapshot reset =
            reversePresentation.publish(
                flow.getSnapshot(), reverseTwin.snapshot(), team.getSnapshot(),
                statuses, received
            );
        require("--".equals(reset.getBottleSize(
                ABSVisualisationFlowModel.CAPPER, 1).getSizeCode()),
            "reset clears stale live size geometry");
        require(!reset.getBottleSize(
                ABSVisualisationFlowModel.CAPPER, 1).isConfirmed(),
            "reset restores capability/symbolic size evidence");
        double neutralTarget = unknownTarget();
        require(BottleVisualGeometry.interpolateWorkingY(
                Double.NaN, neutralTarget) == neutralTarget,
            "reset interpolation starts at a neutral safe target");
    }

    private static double unknownTarget() {
        return BottleVisualGeometry.forSizeCode("--")
            .workingTopY(400.0, 130, 5.0);
    }

    private static String snapshot(
        String generation,
        long sequence,
        String bottle,
        String size,
        int capacity
    ) {
        return "V2|TWIN|" + generation + "|" + sequence +
            "|W=1|R=1|REJECTED=0|WORKPIECES=" + bottle +
            ",LOADED,BottleLoaderControllerCD," + sequence + "," + size +
            "," + capacity +
            "|RESOURCES=BottleLoaderControllerCD,BottleLoader," + bottle +
            ",2,LOAD_CONFIRMED,NONE," + sequence;
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
