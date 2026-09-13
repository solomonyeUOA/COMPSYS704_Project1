/** Read-only overview/detail evidence projection and visual scaling regressions. */
public final class ABSVisualisationPresentationSelfTest {
    private static int assertions;

    private ABSVisualisationPresentationSelfTest() { }

    public static void main(String[] args) {
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        ABSVisualisationTeamIpModel team = new ABSVisualisationTeamIpModel();
        ABSVisualisationPresentationModel presentation =
            new ABSVisualisationPresentationModel();
        int[] statuses = new int[ABSVisualisationFlowModel.MODULE_COUNT];
        boolean[] received = new boolean[ABSVisualisationFlowModel.MODULE_COUNT];
        flow.acceptRequired(2);

        ABSVisualisationPresentationModel.Snapshot empty = presentation.publish(
            flow.getSnapshot(), null, team.getSnapshot(), statuses, received
        );
        require("--".equals(empty.getActiveOrder()),
            "unknown order is not fabricated");
        require(empty.getBottleContext().indexOf("Awaiting live evidence") >= 0,
            "zero-bottle view explains missing evidence");
        require(!empty.getModule(ABSVisualisationFlowModel.CAPPER).isConfirmed(),
            "module without Twin evidence remains symbolic/unknown");
        require("--".equals(empty.getBottleSize(
                ABSVisualisationFlowModel.CAPPER, 1).getSizeCode()),
            "missing live/order context cannot fabricate a bottle size");

        presentation.recordStatus(ABSVisualisationFlowModel.LOADER, "READY");
        received[ABSVisualisationFlowModel.LOADER] = true;
        statuses[ABSVisualisationFlowModel.LOADER] = 1;
        ABSLiveTwinModel twin = new ABSLiveTwinModel();
        require(twin.accept(
            "V2|TWIN|77|9|W=2|R=2|REJECTED=0|" +
            "WORKPIECES=PO42-B1,LOADED,BottleLoaderControllerCD,2,S,200;" +
            "PO42-B2,SORTED,SortPackControllerCD,3,L,500|" +
            "RESOURCES=BottleLoaderControllerCD,BottleLoader,PO42-B1,1," +
            "LOAD_CONFIRMED,NONE,4;SortPackControllerCD,SortPack,PO42-B2,2," +
            "ROUTE_L,NO_FAULT,7"
        ), "valid multi-bottle Twin snapshot accepted");
        presentation.observeTwin(twin.snapshot());
        team.acceptTwinEvidence(twin.snapshot());
        ABSVisualisationPresentationModel.Snapshot live = presentation.publish(
            flow.getSnapshot(), twin.snapshot(), team.getSnapshot(), statuses,
            received
        );
        require("PO42".equals(live.getActiveOrder()),
            "active order derives only from confirmed bottle identity");
        require(live.getBottleContext().indexOf("PO42-B2") >= 0 &&
            live.getBottleContext().indexOf("L / 500 mL") >= 0,
            "latest confirmed bottle context is displayed");

        ABSVisualisationPresentationModel.ModuleContext loader =
            live.getModule(ABSVisualisationFlowModel.LOADER);
        require(loader.isConfirmed(), "loader context is confirmed");
        require("PO42-B1".equals(loader.getBottleId()),
            "resource relationship selects matching bottle");
        require("S".equals(loader.getSizeCode()) &&
            "200 mL".equals(loader.getCapacity()),
            "small size context preserved");
        require(loader.getHistory().size() >= 2,
            "status and Twin events form a real evidence history");

        ABSVisualisationPresentationModel.ModuleContext sort =
            live.getModule(ABSVisualisationFlowModel.SORT_PACK);
        require(sort.isConfirmed() && "L".equals(sort.getSizeCode()),
            "sort detail consumes confirmed large profile");
        require(sort.getOperation().indexOf("ROUTE L") >= 0,
            "confirmed resource operation is exposed");
        require("S".equals(live.getBottleSize(
                ABSVisualisationFlowModel.LOADER, 1).getSizeCode()),
            "overview resolves the confirmed size of symbolic bottle B1");
        require("L".equals(live.getBottleSize(
                ABSVisualisationFlowModel.ROTARY, 2).getSizeCode()),
            "overview resolves each rotary bottle independently");
        require(live.getBottleSize(
                ABSVisualisationFlowModel.ROTARY, 2).isConfirmed(),
            "per-bottle geometry remains tied to confirmed Twin evidence");
        require("L".equals(live.getBottleSize(
                ABSVisualisationFlowModel.CONVEYOR, 99).getSizeCode()) &&
            live.getBottleSize(ABSVisualisationFlowModel.CONVEYOR, 99)
                .getSource().indexOf("CURRENT ORDER") >= 0,
            "confirmed active-order size is the explicit secondary fallback");
        require(ABSVisualisationPresentationModel.bottleScale("S") <
            ABSVisualisationPresentationModel.bottleScale("L"),
            "S and L use visibly different geometry scales");
        require(ABSVisualisationPresentationModel.bottleScale("--") == 1.0,
            "unknown size uses neutral scale");

        presentation.reset("RST7700");
        ABSVisualisationPresentationModel.Snapshot reset = presentation.publish(
            flow.getSnapshot(), null, team.getSnapshot(), statuses, received
        );
        require(reset.getModule(ABSVisualisationFlowModel.LOADER)
            .getHistory().size() == 1,
            "reset clears stale detail activity");
        require(reset.getModule(ABSVisualisationFlowModel.LOADER)
            .getHistory().get(0).indexOf("awaiting new live evidence") >= 0,
            "reset does not fabricate completion");

        System.out.println("ABSVisualisationPresentationSelfTest PASSED assertions=" +
            assertions + " (zero/multiple Twin rows, context, history, S/L scaling)");
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
