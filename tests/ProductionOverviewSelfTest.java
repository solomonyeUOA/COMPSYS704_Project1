import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Native Swing overview/detail regressions. Rendered evidence is a test fixture. */
public final class ProductionOverviewSelfTest {
    private static int assertions;
    private static final String[] NAMES = {
        "Bottle Loader", "Conveyor", "Rotary Turntable", "Filler A", "Filler B",
        "Lid Loader", "Capper", "Labeller", "Bottle Unloader", "Sort / Pack"
    };
    private static final int[][] CENTRES = {
        {78,224}, {205,224}, {354,220}, {516,141}, {516,305},
        {663,224}, {796,224}, {933,224}, {1089,224}, {1260,224}
    };

    private ProductionOverviewSelfTest() { }

    public static void main(final String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Optional PNG output directory");
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try { exercise(args.length == 0 ? null : new File(args[0])); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
        System.out.println("ProductionOverviewSelfTest PASSED assertions=" + assertions +
            " (10 stations, click/detail/status routing, fault freeze; renders are fixtures)");
    }

    private static void exercise(File output) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "Swing work on EDT");
        require(ABSVisualisationFlowModel.MODULE_COUNT == 10, "ten explicit stations");
        int[] journey = {ABSVisualisationFlowModel.LID, ABSVisualisationFlowModel.CAPPER,
            ABSVisualisationFlowModel.LABELLER, ABSVisualisationFlowModel.UNLOADER,
            ABSVisualisationFlowModel.SORT_PACK};
        for (int i = 1; i < journey.length; i++) {
            require(journey[i] == journey[i - 1] + 1, "LID > CAP > Label > Unloader > Sort/Pack");
            require(CENTRES[journey[i]][0] > CENTRES[journey[i - 1]][0], "downstream visual order");
        }
        final int[] opened = {-1};
        ABSVisualisation.ProductionLinePanel overview = overviewWithClickRecorder(opened);
        require(overview.getPreferredSize().width == 1360, "overview accommodates all modules");
        require(overview.getPreferredSize().height == 528,
            "overview retains process coordinates plus the read-only redundancy strip");
        for (int i = 0; i < NAMES.length; i++) {
            require(overview.moduleAtDesignPoint(CENTRES[i][0], CENTRES[i][1]) == i,
                "distinct hit region for " + NAMES[i]);
        }
        require(overview.moduleAtDesignPoint(0, 0) == -1, "background is not a station");
        require(overview.moduleAtDesignPoint(1006, 224) == -1, "label/unloader connector is not a station");
        require(overview.moduleAtDesignPoint(1350, 224) == -1, "right margin is not sort/pack");
        // Exercise the actual scaled mouse listener and tooltip conversion.
        overview.setSize(680, 264);
        for (int i = 0; i < NAMES.length; i++) {
            MouseEvent click = new MouseEvent(overview, MouseEvent.MOUSE_CLICKED,
                1L, 0, CENTRES[i][0] / 2, CENTRES[i][1] / 2,
                0, 0, 1, false, MouseEvent.BUTTON1);
            overview.dispatchEvent(click);
            require(opened[0] == i, "scaled click opens " + NAMES[i]);
            require(overview.getToolTipText(click).contains(NAMES[i]), "tooltip names " + NAMES[i]);
        }

        ABSVisualisation.resetSystem("RST9900");
        ABSVisualisation.updateRequiredBottles(1);
        ABSVisualisationFlowModel flow = flowModel();

        for (int stage = 0; stage <= ABSVisualisationFlowModel.CAPPER; stage++) complete(stage, flow);
        ABSVisualisation.ModuleDetailPanel label = new ABSVisualisation.ModuleDetailPanel(7);
        ABSVisualisation.ModuleDetailPanel capper = new ABSVisualisation.ModuleDetailPanel(6);
        ABSVisualisation.ModuleDetailPanel sort = new ABSVisualisation.ModuleDetailPanel(9);
        require(buttons(label) == 0 && buttons(capper) == 0 &&
            buttons(sort) == 0, "detail panels have no control buttons");
        require(text(label).contains("LABELLER"), "labeller detail title");
        require(text(capper).contains("CAPPER"), "capper detail title");
        require(text(sort).contains("SORT / PACK"), "sort/pack detail title");

        require(ABSVisualisation.updateM4CapperState(
            "V1|LIVE-L-B001|L|GEOM_L|LOWERING|2"
        ), "valid M4 Capper telemetry accepted");
        require(!ABSVisualisation.updateCoordinatorCapperStatus(3),
            "delayed Coordinator Capper status cannot overwrite direct M4 telemetry");
        require(ABSVisualisation.updateM4CapperState(
            "V1|LIVE-L-B001|L|GEOM_L|LOWERING|2"
        ), "duplicate direct M4 Capper telemetry remains idempotent");
        capper.syncRealState();
        require(capper.getDisplayedRealStatus() == 2,
            "M4 Capper telemetry updates real status");
        require(text(capper).contains("LIVE-L-B001") &&
            text(capper).contains("GEOM_L") &&
            text(capper).contains("LOWERING"),
            "Capper detail displays live bottle, geometry and arm stage");
        require(!ABSVisualisation.updateM4CapperState(
            "V1|LIVE-L-B001|S|GEOM_L|LOWERING|2"
        ), "mismatched M4 size and geometry rejected");

        ABSVisualisation.updateLabellerStatus(2);
        tick(flow, 20);
        require(flow.getStartedCycles(7) == 1, "labeller public telemetry reaches flow model");
        require(flow.getModuleSnapshot(7).getCurrentBottleId() == 1, "label holds upstream bottle identity");
        label.syncRealState();
        require(label.getDisplayedRealStatus() == 2, "labeller detail shows BUSY");
        ABSVisualisation.updateLabellerStatus(4);
        double frozen = flow.getModuleSnapshot(7).getProgress();
        tick(flow, 80);
        label.syncRealState();
        require(flow.getModuleSnapshot(7).getProgress() == frozen, "labeller fault freezes animation");
        require(label.getDisplayedRealStatus() == 4, "labeller detail shows FAULT");
        require(label.getDetailModel().getLifecycle() == ABSVisualisationFlowModel.ModuleLifecycle.FAULTED,
            "labeller detail shares faulted snapshot");
        ABSVisualisation.updateLabellerStatus(-1);
        ABSVisualisation.updateLabellerStatus(5);
        label.syncRealState();
        require(label.getDisplayedRealStatus() == 4, "invalid labeller telemetry cannot erase fault");
        ABSVisualisation.updateLabellerStatus(2);
        tick(flow, 150);
        ABSVisualisation.updateLabellerStatus(3);
        tick(flow, 100);
        complete(8, flow);
        ABSVisualisation.updateCompletedBottles(1);
        tick(flow, 100);
        require(flow.getVisualCompleted() == 0, "unload count alone cannot fabricate sort/pack");
        ABSVisualisation.updateStatus("Sort / Pack", 2);
        tick(flow, 20);
        sort.syncRealState();
        require(flow.getStartedCycles(9) == 1, "sort/pack public telemetry reaches flow model");
        require(sort.getDisplayedRealStatus() == 2, "sort detail shows BUSY");
        require(sort.getDetailModel().getCurrentBottleId() == 1, "sort shares bottle identity");
        ABSVisualisation.updateStatus("Sort / Pack", 4);
        frozen = flow.getModuleSnapshot(9).getProgress();
        tick(flow, 80);
        sort.syncRealState();
        require(flow.getModuleSnapshot(9).getProgress() == frozen, "sort fault freezes animation");
        require(sort.getDisplayedRealStatus() == 4, "sort detail shows FAULT");
        require(flow.getVisualCompleted() == 0, "sort fault cannot complete batch");
        ABSVisualisation.updateStatus("Sort / Pack", 2);
        tick(flow, 150);
        ABSVisualisation.updateStatus("Sort / Pack", 3);
        tick(flow, 100);
        require(flow.getVisualCompleted() == 1, "real sort evidence completes full journey");
        require(flow.invariantsHold(), "all flow invariants hold");

        ABSVisualisation.resetSystem("RST9901");
        for (int i = 0; i < NAMES.length; i++) {
            if (i == 7) ABSVisualisation.updateLabellerStatus(1);
            else if (i == 6) require(
                ABSVisualisation.updateCoordinatorCapperStatus(1),
                "Coordinator Capper fallback is re-enabled after reset"
            );
            else ABSVisualisation.updateStatus(NAMES[i], 1);
        }
        tick(flow, 1);
        label.syncRealState();
        sort.syncRealState();
        require(label.getDisplayedRealStatus() == 1 && sort.getDisplayedRealStatus() == 1,
            "new stations return READY after reset");
        for (int i = 0; i < NAMES.length; i++) {
            ABSVisualisation.ModuleDetailPanel detail =
                new ABSVisualisation.ModuleDetailPanel(i);
            require(text(detail).contains(NAMES[i].toUpperCase()),
                "professional detail identity for " + NAMES[i]);
            require(buttons(detail) == 0,
                "read-only detail has no application controls for " + NAMES[i]);
            require(detail.getDetailModel() == flow.getSnapshot().getModule(i),
                "overview/detail share immutable module snapshot for " + NAMES[i]);
            detail.stopAnimation();
        }
        ABSVisualisation.OverviewSummaryPanel summary =
            new ABSVisualisation.OverviewSummaryPanel();
        summary.syncState();
        require(text(summary).contains("ACTIVE ORDER") &&
            text(summary).contains("SYSTEM STATE"),
            "overview has complete evidence summary row");
        require(text(summary).contains("--"),
            "summary retains unknown markers without fabricated telemetry");
        exerciseTeamIpOverview(output);
        if (output != null) {
            render(overview, output, "production-overview-label-sort.png", 1400, 470);
            render(summary, output, "production-summary-fixture.png", 1400, 120);
            render(label, output, "labeller-detail-fixture.png", 820, 620);
            render(sort, output, "sort-pack-detail-fixture.png", 820, 620);
        }
        label.stopAnimation();
        capper.stopAnimation();
        sort.stopAnimation();
        exerciseSizeFixtures(output);
    }

    private static void exerciseTeamIpOverview(File output) throws Exception {
        final int[] opened = {-1};
        ABSVisualisation.TeamIpExtensionsPanel extensions =
            teamIpOverviewWithClickRecorder(opened);
        JButton[] cards = teamIpCards(extensions);
        require(cards.length == 2,
            "Team-IP overview renders exactly two extension cards");

        String m2 = cards[0].getAccessibleContext().getAccessibleName();
        String m4 = cards[1].getAccessibleContext().getAccessibleName();
        require(m2.indexOf("M2 DIGITAL TWIN") >= 0,
            "M2 Digital Twin card remains visible");
        require(m4.indexOf("M4 TWO-SIZE EXTENSION") >= 0,
            "M4 Two-Size Extension card remains visible");
        require((m2 + " " + m4).indexOf("M3 FAULT TOLERANCE") < 0,
            "M3 Fault Tolerance card is absent from the overview");

        Container cardRow = cards[0].getParent();
        require(cardRow == cards[1].getParent() &&
            cardRow.getComponentCount() == 2,
            "M2 and M4 are the only overview columns");
        require(cardRow.getLayout() instanceof java.awt.GridBagLayout,
            "IP columns use weighted layout");
        require(cardRow.getPreferredSize().height >= 100,
            "Team-IP summaries reserve room for diagram, status and detail link");
        extensions.setSize(1400, 202);
        layout(extensions);
        require(cards[0].getWidth() == cards[1].getWidth() &&
            cards[0].getWidth() > 400,
            "M2 and M4 divide the available width evenly");

        cards[0].doClick();
        require(opened[0] == ABSVisualisationTeamIpModel.M2_DIGITAL_TWIN,
            "M2 card still opens M2 detail");
        cards[1].doClick();
        require(opened[0] == ABSVisualisationTeamIpModel.M4_TWO_SIZE,
            "M4 card still opens M4 detail");

        // The M3 observation/detail model remains intact even though its
        // overview launcher is deliberately hidden.
        ABSVisualisation.TeamIpDetailPanel m3Detail =
            new ABSVisualisation.TeamIpDetailPanel(
                ABSVisualisationTeamIpModel.M3_FAULT_TOLERANCE
            );
        require(m3Detail != null,
            "M3 fault presentation/data path remains available");

        if (output != null) {
            render(extensions, output, "team-ip-overview-m2-m4-fixture.png",
                1400, 190);
        }
    }

    /** Explicit multi-station fixtures, isolated from no-Twin symbolic replay. */
    private static void exerciseSizeFixtures(File output) throws Exception {
        ABSVisualisation.resetSystem("RST9902");
        ABSVisualisation.updateRequiredBottles(10);
        ABSVisualisation.updateTwinSnapshot(sizeFixture(1, "S", 200));
        ABSVisualisation.ModuleDetailPanel[] sizeDetails =
            new ABSVisualisation.ModuleDetailPanel[NAMES.length];
        for (int index = 0; index < sizeDetails.length; index++) {
            sizeDetails[index] = new ABSVisualisation.ModuleDetailPanel(index);
            sizeDetails[index].syncRealState();
            require(sizeDetails[index].getBottleScaleForTest() == 0.82,
                NAMES[index] + " detail uses the common S geometry");
        }
        require(text(sizeDetails[ABSVisualisationFlowModel.LOADER])
                .contains("Small (S) - 200 mL"),
            "loader detail identifies the confirmed small profile");
        if (output != null) {
            render(sizeDetails[ABSVisualisationFlowModel.LOADER], output,
                "loader-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.CONVEYOR], output,
                "conveyor-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.ROTARY], output,
                "rotary-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.FILLER_A], output,
                "filler-a-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.FILLER_B], output,
                "filler-b-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.LID], output,
                "lid-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.CAPPER], output,
                "capper-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.LABELLER], output,
                "labeller-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.UNLOADER], output,
                "unloader-detail-small-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.SORT_PACK], output,
                "sort-pack-detail-small-fixture.png", 820, 620);
        }
        ABSVisualisation.updateTwinSnapshot(sizeFixture(2, "L", 500));
        for (int index = 0; index < sizeDetails.length; index++) {
            sizeDetails[index].syncRealState();
            require(sizeDetails[index].getBottleScaleForTest() == 1.18,
                NAMES[index] + " detail switches to common L geometry");
        }
        require(text(sizeDetails[ABSVisualisationFlowModel.LOADER])
                .contains("Large (L) - 500 mL"),
            "loader detail identifies the confirmed large profile");
        if (output != null) {
            render(sizeDetails[ABSVisualisationFlowModel.LOADER], output,
                "loader-detail-large-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.FILLER_A], output,
                "filler-a-detail-large-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.LID], output,
                "lid-detail-large-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.CAPPER], output,
                "capper-detail-large-fixture.png", 820, 620);
            render(sizeDetails[ABSVisualisationFlowModel.LABELLER], output,
                "labeller-detail-large-fixture.png", 820, 620);
        }
        for (ABSVisualisation.ModuleDetailPanel detail : sizeDetails) {
            detail.stopAnimation();
        }
    }

    private static void complete(int stage, ABSVisualisationFlowModel flow) throws Exception {
        ABSVisualisation.updateStatus(NAMES[stage], 2);
        tick(flow, 150);
        ABSVisualisation.updateStatus(NAMES[stage], 3);
        tick(flow, 100);
    }

    private static ABSVisualisationFlowModel flowModel() throws Exception {
        Field field = ABSVisualisation.class.getDeclaredField("VISUAL_MODEL");
        field.setAccessible(true);
        return (ABSVisualisationFlowModel)field.get(null);
    }

    private static String sizeFixture(int sequence, String size, int capacity) {
        String[] resources = {"BottleLoaderControllerCD", "ConveyorControllerCD",
            "RotaryTableControllerCD", "FillerAControllerCD", "FillerBControllerCD",
            "LidLoaderControllerCD", "CapperControllerCD", "LabellerControllerCD",
            "BottleUnloaderControllerCD", "SortPackControllerCD"};
        String[] types = {"LOADER", "CONVEYOR", "ROTARY", "FILLER_A", "FILLER_B",
            "LID", "CAPPER", "LABELLER", "UNLOADER", "SORTPACK"};
        String[] stages = {"LOADED", "LOADED", "P1", "P1", "FILLED", "LIDDED",
            "CAPPED", "LABELLED", "UNLOADED", "SORTED"};
        StringBuilder bottles = new StringBuilder();
        StringBuilder observations = new StringBuilder();
        for (int index = 0; index < 10; index++) {
            if (index > 0) { bottles.append(';'); observations.append(';'); }
            String bottle = String.format("PO9900-P01-B%03d", index + 1);
            bottles.append(bottle).append(',').append(stages[index]).append(',')
                .append(resources[index]).append(',').append(sequence).append(',')
                .append(size).append(',').append(capacity);
            observations.append(resources[index]).append(',').append(types[index])
                .append(',').append(bottle).append(",2,TEST_FIXTURE,NONE,").append(sequence);
        }
        return "V2|TWIN|9903|" + sequence + "|W=10|R=10|REJECTED=0|WORKPIECES=" +
            bottles + "|RESOURCES=" + observations;
    }

    /** Observe the private read-only dialog callback without opening a headless JFrame. */
    private static ABSVisualisation.ProductionLinePanel overviewWithClickRecorder(final int[] opened)
        throws Exception {
        Class<?> opener = Class.forName("ABSVisualisation$DetailWindowOpener");
        Object recorder = Proxy.newProxyInstance(opener.getClassLoader(), new Class<?>[] {opener},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("openDetail".equals(method.getName())) opened[0] = ((Integer)args[0]).intValue();
                    return null;
                }
            });
        Constructor<ABSVisualisation.ProductionLinePanel> constructor =
            ABSVisualisation.ProductionLinePanel.class.getDeclaredConstructor(opener);
        constructor.setAccessible(true);
        return constructor.newInstance(recorder);
    }

    private static ABSVisualisation.TeamIpExtensionsPanel
        teamIpOverviewWithClickRecorder(final int[] opened) throws Exception {
        Class<?> opener = Class.forName("ABSVisualisation$TeamIpWindowOpener");
        Object recorder = Proxy.newProxyInstance(
            opener.getClassLoader(),
            new Class<?>[] {opener},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("openTeamIpDetail".equals(method.getName())) {
                        opened[0] = ((Integer)args[0]).intValue();
                    }
                    return null;
                }
            }
        );
        Constructor<ABSVisualisation.TeamIpExtensionsPanel> constructor =
            ABSVisualisation.TeamIpExtensionsPanel.class
                .getDeclaredConstructor(opener);
        constructor.setAccessible(true);
        return constructor.newInstance(recorder);
    }

    private static JButton[] teamIpCards(
        ABSVisualisation.TeamIpExtensionsPanel extensions
    ) throws Exception {
        Field field = ABSVisualisation.TeamIpExtensionsPanel.class
            .getDeclaredField("cards");
        field.setAccessible(true);
        return (JButton[])field.get(extensions);
    }

    /** No JFrame/timer in headless tests; publish exactly the timer's immutable snapshot. */
    private static void tick(ABSVisualisationFlowModel flow, int count) throws Exception {
        for (int i = 0; i < count; i++) flow.tick();
        Field field = ABSVisualisation.class.getDeclaredField("renderSnapshot");
        field.setAccessible(true);
        field.set(null, flow.getSnapshot());
    }

    private static int buttons(Component component) {
        // Ignore Swing's internal JScrollPane arrow buttons; only application
        // buttons could represent a prohibited visualisation control output.
        int count = component.getClass() == JButton.class ? 1 : 0;
        if (component instanceof Container) {
            for (Component child : ((Container)component).getComponents()) count += buttons(child);
        }
        return count;
    }

    private static String text(Component component) {
        String result = component instanceof JLabel ? ((JLabel)component).getText() : "";
        if (component instanceof Container) {
            for (Component child : ((Container)component).getComponents()) result += " " + text(child);
        }
        return result;
    }

    private static void render(JPanel panel, File directory, String filename, int width, int height)
        throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create " + directory);
        JPanel wrapper = new JPanel(new BorderLayout());
        JLabel title = new JLabel("TEST FIXTURE - ALL STATIONS READY - NOT A LIVE PRODUCTION CAPTURE");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        title.setOpaque(true);
        title.setBackground(new Color(255, 235, 160));
        title.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        wrapper.add(title, BorderLayout.NORTH);
        wrapper.add(panel, BorderLayout.CENTER);
        wrapper.setSize(width, height);
        wrapper.addNotify();
        try {
            layout(wrapper);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { wrapper.printAll(graphics); }
            finally { graphics.dispose(); }
            File target = new File(directory, filename);
            require(ImageIO.write(image, "png", target), "PNG renderer available");
            System.out.println("TEST_FIXTURE_RENDER " + target.getAbsolutePath());
        }
        finally { wrapper.removeNotify(); wrapper.remove(panel); }
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container) layout((Container)child);
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
