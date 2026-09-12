import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/** Headless Swing fixtures: accepted twin evidence and the rendered line must agree. */
public final class TwinOverviewConsistencySelfTest {
    private static final String FIRST = "QA-PO0001-B001";
    private static final String SECOND = "QA-PO0001-B002";
    private static int assertions;

    private TwinOverviewConsistencySelfTest() { }

    public static void main(final String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Optional PNG output directory");
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try { exercise(args.length == 0 ? null : new File(args[0])); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
        System.out.println("TwinOverviewConsistencySelfTest PASSED assertions=" + assertions +
            " (headless fixtures: immediate completion, shared identities, second bottle, capper, reset race)");
    }

    private static void exercise(File output) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "all UI checks run on EDT");
        ABSVisualisation.resetSystem("RST9800");
        ABSVisualisation.updateRequiredBottles(2);
        ABSVisualisationFlowModel flow = flowModel();
        // Reproduce the previous mismatch: a GP-only symbolic journey is still
        // at a filler when the controller's bottle twins already report COMPLETE.
        String[] upstream = {"Bottle Loader", "Conveyor", "Rotary Turntable"};
        for (String name : upstream) {
            ABSVisualisation.updateStatus(name, 2);
            tick(flow, 150);
            ABSVisualisation.updateStatus(name, 3);
            tick(flow, 100);
        }
        ABSVisualisation.updateStatus("Filler A", 2);
        tick(flow, 20);
        require(!flow.getSnapshot().isTwinDriven(), "legacy fallback used before twin evidence");
        require(flow.getModuleSnapshot(ABSVisualisationFlowModel.FILLER_A)
            .getCurrentBottleId() > 0, "fixture starts with a symbolic bottle at Filler A");

        ABSVisualisation.TeamIpDetailPanel m2 = new ABSVisualisation.TeamIpDetailPanel(
            ABSVisualisationTeamIpModel.M2_DIGITAL_TWIN);
        ABSVisualisation.TeamIpDetailPanel m4 = new ABSVisualisation.TeamIpDetailPanel(
            ABSVisualisationTeamIpModel.M4_TWO_SIZE);
        ABSVisualisation.ModuleDetailPanel filler = new ABSVisualisation.ModuleDetailPanel(
            ABSVisualisationFlowModel.FILLER_B);
        try {
            String completed = completedFixture("9801", 1L);
            ABSVisualisation.updateTwinSnapshot(completed);
            // No ticks, sleeps, repaint loops or event-queue drains after update.
            assertCompletedImmediately(flow, m2, m4, filler);
            if (output != null) {
                render(new ABSVisualisation.ProductionLinePanel(), output,
                    "twin-complete-overview-fixture.png", 1400, 470);
                render(filler, output, "twin-complete-filler-detail-fixture.png", 900, 650);
            }
            ABSVisualisationFlowModel.FlowSnapshot accepted = rendered();
            ABSVisualisation.updateTwinSnapshot(completed);
            ABSVisualisation.updateTwinSnapshot(completed.replace("W=2", "W=3"));
            require(rendered() == accepted,
                "duplicate and malformed snapshots cannot replace the published render");

            ABSVisualisation.resetSystem("RST9801");
            ABSVisualisation.updateRequiredBottles(2);
            ABSVisualisation.updateCompletedBottles(1);
            ABSVisualisation.updateStatus("Filler A", 2);
            ABSVisualisation.updateTwinSnapshot(partialFixture("9802", 1L));
            ABSVisualisationFlowModel.FlowSnapshot partial = rendered();
            require(partial == flow.getSnapshot(), "accepted partial twin is rendered immediately");
            require(partial.isTwinDriven(), "partial twin is authoritative for bottle positions");
            require(partial.getTwinCompleted() == 1 && partial.getTwinBottleCount() == 2,
                "one finished bottle does not hide the second live bottle");
            ABSVisualisationFlowModel.ModuleSnapshot held = partial.getModule(
                ABSVisualisationFlowModel.FILLER_B);
            require(SECOND.equals(held.getCurrentBottleKey()),
                "overview displays the actual second bottle identity");
            require(!held.isRunning(), "last confirmed fill is not an invented active operation");
            require(held.getLifecycle() == ABSVisualisationFlowModel.ModuleLifecycle.HOLDING,
                "filled bottle waits for the next confirmed observation");
            assertIdentityAndTables(partial, m2, m4, "FILLED");
            filler.syncRealState();
            require(filler.getDetailModel() == held, "detail and overview share the same immutable module");
            require(text(filler).contains(SECOND), "detail names the same actual B002 as the twin table");
            for (int module = 0; module < ABSVisualisationFlowModel.MODULE_COUNT; module++) {
                require(!FIRST.equals(partial.getModule(module).getCurrentBottleKey()),
                    "completed B001 cannot remain at any workstation");
                require(hasObservedBottle(module) == (module == ABSVisualisationFlowModel.FILLER_B),
                    "paint guard draws only the second bottle at its confirmed stage");
            }
            tick(flow, 500);
            require(flow.getSnapshot().getTwinCompleted() == 1,
                "elapsed animation ticks cannot fabricate the second completion");
            require(SECOND.equals(flow.getSnapshot().getModule(
                ABSVisualisationFlowModel.FILLER_B).getCurrentBottleKey()),
                "the second bottle remains at its confirmed stage until evidence changes");
            ABSVisualisation.updateCompletedBottles(2);
            require(flow.getSnapshot().getTwinCompleted() == 1,
                "GP unloaded count is not twin Sort/Pack completion");
            ABSVisualisation.updateTwinSnapshot(completedFixture("9802", 2L));
            assertCompletedImmediately(flow, m2, m4, filler);

            ABSVisualisation.resetSystem("RST9802");
            ABSVisualisation.updateTwinSnapshot(completedFixture("9802", 999L));
            m2.syncState();
            m4.syncState();
            require(table(m2, "Live workpieces").getRowCount() == 0 &&
                table(m4, "Live workpieces").getRowCount() == 0,
                "pre-reset generation cannot restore old bottle rows");
            require(rendered().getBottles().isEmpty(), "pre-reset bottles cannot reappear on the line");
            ABSVisualisation.updateTwinSnapshot(
                "V2|TWIN|9803|1|W=0|R=0|REJECTED=0|WORKPIECES=|RESOURCES=");
            require(rendered() == flow.getSnapshot() && rendered().getBottles().isEmpty(),
                "empty fresh-generation twin immediately clears the same render model");
            assertNoMachineBottle(rendered());

            exerciseCapperIdentity(m2, m4);

            // TCP delivery can show a new-generation snapshot before the reset
            // notification. Reset must clear older evidence, not erase this one.
            ABSVisualisation.updateTwinSnapshot(partialFixture("9804", 1L));
            require(SECOND.equals(rendered().getModule(ABSVisualisationFlowModel.FILLER_B)
                .getCurrentBottleKey()), "new generation arrives before its reset notification");
            ABSVisualisation.resetSystem("RST9803");
            ABSVisualisationFlowModel.FlowSnapshot retained = rendered();
            require(retained == flow.getSnapshot() && retained.isTwinDriven(),
                "reset immediately republishes an already accepted fresh-generation twin");
            require(retained.getTwinCompleted() == 1 && retained.getTwinBottleCount() == 2,
                "late reset retains fresh-generation bottle counts");
            require(SECOND.equals(retained.getModule(ABSVisualisationFlowModel.FILLER_B)
                .getCurrentBottleKey()), "late reset preserves the current B002 location");
            assertIdentityAndTables(retained, m2, m4, "FILLED");
            ABSVisualisation.updateTwinSnapshot(completedFixture("9804", 2L));
            assertCompletedImmediately(flow, m2, m4, filler);
        }
        finally { filler.stopAnimation(); }
    }

    private static void exerciseCapperIdentity(ABSVisualisation.TeamIpDetailPanel m2,
        ABSVisualisation.TeamIpDetailPanel m4) throws Exception {
        require(ABSVisualisation.updateM4CapperState(
            "V1|" + FIRST + "|S|GEOM_S|TWISTING|2"), "valid earlier B001 capper telemetry accepted");
        String capped = partialFixture("9803", 2L).replace(
            ",FILLED,FILLER_A,4,L,500", ",CAPPED,CAPPER,6,L,500");
        ABSVisualisation.updateTwinSnapshot(capped);
        ABSVisualisationFlowModel.ModuleSnapshot capper = rendered().getModule(
            ABSVisualisationFlowModel.CAPPER);
        require(SECOND.equals(capper.getCurrentBottleKey()), "capper currently projects B002 from its twin");
        require(currentBottleIsLarge(ABSVisualisationFlowModel.CAPPER),
            "B002 large profile comes from its own twin, not the old B001 small profile");
        require(capperTelemetryForCurrentBottle() == null,
            "B001 arm telemetry cannot be applied to B002");
        require(capperProgress(100.0) == 100.0,
            "B001 TWISTING must not replace B002 confirmed 100 percent with 52 percent");
        M4CapperTelemetryV1 raw = (M4CapperTelemetryV1)privateStatic("capperTelemetry");
        require(FIRST.equals(raw.getBottleId()),
            "ignoring mismatched telemetry does not fabricate or erase the raw observation");
        assertIdentityAndTables(rendered(), m2, m4, "CAPPED");

        require(ABSVisualisation.updateM4CapperState(
            "V1|" + SECOND + "|L|GEOM_L|TWISTING|2"), "matching B002 telemetry is syntactically accepted");
        require(capperTelemetryForCurrentBottle() == null,
            "older BUSY arm stage cannot undo B002 already-confirmed CAPPED stage");
        require(capperProgress(100.0) == 100.0,
            "matching but obsolete TWISTING telemetry cannot rewind completed capping");
        require(!flowModel().getSnapshot().getModule(ABSVisualisationFlowModel.CAPPER).isRunning(),
            "obsolete arm state cannot reactivate confirmed capped work");
        require(currentBottleIsLarge(ABSVisualisationFlowModel.CAPPER),
            "B002 keeps its own large-bottle geometry");
        require(ABSVisualisation.updateM4CapperState(
            "V1|" + SECOND + "|L|GEOM_L|DONE|3"), "matching completed arm observation is accepted");
        require(capperTelemetryForCurrentBottle() != null && capperProgress(0.0) == 100.0,
            "matching non-conflicting DONE telemetry remains available for the current bottle");
    }

    private static void assertCompletedImmediately(ABSVisualisationFlowModel flow,
        ABSVisualisation.TeamIpDetailPanel m2, ABSVisualisation.TeamIpDetailPanel m4,
        ABSVisualisation.ModuleDetailPanel filler) throws Exception {
        ABSVisualisationFlowModel.FlowSnapshot snapshot = rendered();
        require(snapshot == flow.getSnapshot(), "render publishes the accepted twin without a timer tick");
        require(snapshot.isTwinDriven(), "completion switches to actual twin projection");
        require(snapshot.getTwinCompleted() == 2 && snapshot.getTwinBottleCount() == 2,
            "both confirmed twin completions are visible immediately");
        assertNoMachineBottle(snapshot);
        assertIdentityAndTables(snapshot, m2, m4, "COMPLETE");
        filler.syncRealState();
        require(filler.getDetailModel() == snapshot.getModule(ABSVisualisationFlowModel.FILLER_B),
            "detail completion uses the overview snapshot without independent playback");
        JTable resources = table(m2, "Live resources");
        require("-".equals(resources.getModel().getValueAt(1, 2)),
            "idle resource with no current bottle displays '-' rather than stale B001");
    }

    private static void assertNoMachineBottle(ABSVisualisationFlowModel.FlowSnapshot snapshot)
        throws Exception {
        for (int module = 0; module < ABSVisualisationFlowModel.MODULE_COUNT; module++) {
            require(snapshot.getModule(module).getCurrentBottleId() == 0,
                "no completed bottle remains in module " + module);
            require(!snapshot.getModule(module).isRunning(),
                "module " + module + " cannot animate a completed bottle");
            require(!hasObservedBottle(module),
                "overview/detail paint guard hides completed bottle at module " + module);
        }
    }

    private static void assertIdentityAndTables(ABSVisualisationFlowModel.FlowSnapshot snapshot,
        ABSVisualisation.TeamIpDetailPanel m2, ABSVisualisation.TeamIpDetailPanel m4,
        String secondStage) {
        require(snapshot.getBottles().size() == 2, "exactly two unique actual bottles in the flow");
        for (ABSVisualisation.TeamIpDetailPanel panel :
                new ABSVisualisation.TeamIpDetailPanel[] {m2, m4}) {
            panel.syncState();
            JTable bottles = table(panel, "Live workpieces");
            JTable resources = table(panel, "Live resources");
            require(bottles.getModel().getRowCount() == 2, "M2/M4 each display two bottle rows");
            require(resources.getModel().getRowCount() == 3, "M2/M4 each display latest resource rows");
            require("COMPLETE".equals(bottles.getModel().getValueAt(0, 1)), "B001 stays complete");
            require(secondStage.equals(bottles.getModel().getValueAt(1, 1)), "B002 displays current stage");
            require(SECOND.equals(resources.getModel().getValueAt(0, 2)),
                "filler resource advances to the second bottle in both IP tabs");
            require("Last confirmed operation".equals(resources.getModel().getValueAt(0, 7)),
                "historical fill confirmation is identified as observation, not live occupancy");
            for (int row = 0; row < 2; row++) {
                ABSVisualisationFlowModel.BottleSnapshot bottle = snapshot.getBottles().get(row);
                require(bottle.getBottleKey().equals(bottles.getModel().getValueAt(row, 0)),
                    "flow and workpiece table use identical real bottle IDs");
                require(bottle.getTwinStage().equals(bottles.getModel().getValueAt(row, 1)),
                    "flow and workpiece table show identical confirmed stages");
            }
        }
    }

    private static String completedFixture(String generation, long sequence) {
        return "V2|TWIN|" + generation + "|" + sequence +
            "|W=2|R=3|REJECTED=0|WORKPIECES=" +
            FIRST + ",COMPLETE,SORT_PACK,11,S,200;" +
            SECOND + ",COMPLETE,SORT_PACK,11,L,500|RESOURCES=" +
            "FILLER_A,FILLER_A," + SECOND + ",3,OBSERVED_FILLED,-,12;" +
            "LABELLER-1,LABELLER,-,1,WAIT_JOB,-,13;" +
            "SORT_PACK,SORTPACK," + SECOND + ",3,OBSERVED_SORTED,-,14";
    }

    private static String partialFixture(String generation, long sequence) {
        return "V2|TWIN|" + generation + "|" + sequence +
            "|W=2|R=3|REJECTED=0|WORKPIECES=" +
            FIRST + ",COMPLETE,SORT_PACK,11,S,200;" +
            SECOND + ",FILLED,FILLER_A,4,L,500|RESOURCES=" +
            "FILLER_A,FILLER_A," + SECOND + ",3,OBSERVED_FILLED,-,12;" +
            "LABELLER-1,LABELLER,-,1,WAIT_JOB,-,13;" +
            "SORT_PACK,SORTPACK," + FIRST + ",3,OBSERVED_SORTED,-,7";
    }

    private static ABSVisualisationFlowModel flowModel() throws Exception {
        return (ABSVisualisationFlowModel)field("VISUAL_MODEL").get(null);
    }

    private static ABSVisualisationFlowModel.FlowSnapshot rendered() throws Exception {
        return (ABSVisualisationFlowModel.FlowSnapshot)field("renderSnapshot").get(null);
    }

    private static Field field(String name) throws Exception {
        Field value = ABSVisualisation.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static boolean hasObservedBottle(int module) throws Exception {
        Method method = ABSVisualisation.class.getDeclaredMethod("hasObservedBottle", int.class);
        method.setAccessible(true);
        return ((Boolean)method.invoke(null, Integer.valueOf(module))).booleanValue();
    }

    private static boolean currentBottleIsLarge(int module) throws Exception {
        Method method = ABSVisualisation.class.getDeclaredMethod("currentBottleIsLarge", int.class);
        method.setAccessible(true);
        return ((Boolean)method.invoke(null, Integer.valueOf(module))).booleanValue();
    }

    private static M4CapperTelemetryV1 capperTelemetryForCurrentBottle() throws Exception {
        return (M4CapperTelemetryV1)privateStatic("capperTelemetryForCurrentBottle");
    }

    private static Object privateStatic(String name) throws Exception {
        Method method = ABSVisualisation.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static double capperProgress(double fallback) throws Exception {
        Method method = ABSVisualisation.class.getDeclaredMethod("capperTelemetryProgress", double.class);
        method.setAccessible(true);
        return ((Double)method.invoke(null, Double.valueOf(fallback))).doubleValue();
    }

    private static void tick(ABSVisualisationFlowModel flow, int count) {
        for (int index = 0; index < count; index++) flow.tick();
    }

    private static JTable table(Container panel, String title) {
        JTabbedPane tabs = tabs(panel);
        require(tabs != null && tabs.indexOfTab(title) >= 0, "tab exists: " + title);
        return (JTable)((JScrollPane)tabs.getComponentAt(tabs.indexOfTab(title)))
            .getViewport().getView();
    }

    private static JTabbedPane tabs(Container panel) {
        for (Component child : panel.getComponents()) {
            if (child instanceof JTabbedPane) return (JTabbedPane)child;
            if (child instanceof Container) {
                JTabbedPane found = tabs((Container)child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String text(Component component) {
        String value = component instanceof JLabel ? ((JLabel)component).getText() : "";
        if (component instanceof Container) {
            for (Component child : ((Container)component).getComponents()) value += " " + text(child);
        }
        return value;
    }

    /** Native Swing paint from explicit test data, never presented as a live capture. */
    private static void render(JPanel panel, File output, String name, int width, int height)
        throws Exception {
        if (!output.isDirectory() && !output.mkdirs()) {
            throw new IllegalStateException("Cannot create fixture output directory " + output);
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        JLabel label = new JLabel(
            "TEST FIXTURE - BOTH BOTTLE TWINS COMPLETE - NOT LIVE PRODUCTION EVIDENCE");
        label.setOpaque(true);
        label.setBackground(new Color(255, 245, 205));
        label.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        wrapper.add(label, BorderLayout.NORTH);
        wrapper.add(panel, BorderLayout.CENTER);
        wrapper.setSize(width, height);
        wrapper.addNotify();
        try {
            layout(wrapper);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { wrapper.printAll(graphics); }
            finally { graphics.dispose(); }
            File target = new File(output, name);
            require(ImageIO.write(image, "png", target), "native PNG renderer is available");
            System.out.println("TEST_FIXTURE_RENDER " + target.getAbsolutePath());
        }
        finally { wrapper.removeNotify(); wrapper.remove(panel); }
    }

    private static void layout(Container panel) {
        panel.doLayout();
        for (Component child : panel.getComponents()) {
            if (child instanceof Container) layout((Container)child);
        }
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
