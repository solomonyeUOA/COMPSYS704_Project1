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
        for (int i = 0; i < NAMES.length; i++) {
            require(overview.moduleAtDesignPoint(CENTRES[i][0], CENTRES[i][1]) == i,
                "distinct hit region for " + NAMES[i]);
        }
        require(overview.moduleAtDesignPoint(0, 0) == -1, "background is not a station");
        require(overview.moduleAtDesignPoint(1006, 224) == -1, "label/unloader connector is not a station");
        require(overview.moduleAtDesignPoint(1350, 224) == -1, "right margin is not sort/pack");
        // Exercise the actual scaled mouse listener and tooltip conversion.
        overview.setSize(680, 210);
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
        ABSVisualisation.ModuleDetailPanel sort = new ABSVisualisation.ModuleDetailPanel(9);
        require(buttons(label) == 0 && buttons(sort) == 0, "new detail panels have no control buttons");
        require(text(label).contains("LABELLER"), "labeller detail title");
        require(text(sort).contains("SORT / PACK"), "sort/pack detail title");

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
            else ABSVisualisation.updateStatus(NAMES[i], 1);
        }
        tick(flow, 1);
        label.syncRealState();
        sort.syncRealState();
        require(label.getDisplayedRealStatus() == 1 && sort.getDisplayedRealStatus() == 1,
            "new stations return READY after reset");
        if (output != null) {
            render(overview, output, "production-overview-label-sort.png", 1400, 470);
            render(label, output, "labeller-detail-fixture.png", 820, 620);
            render(sort, output, "sort-pack-detail-fixture.png", 820, 620);
        }
        label.stopAnimation();
        sort.stopAnimation();
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

    /** No JFrame/timer in headless tests; publish exactly the timer's immutable snapshot. */
    private static void tick(ABSVisualisationFlowModel flow, int count) throws Exception {
        for (int i = 0; i < count; i++) flow.tick();
        Field field = ABSVisualisation.class.getDeclaredField("renderSnapshot");
        field.setAccessible(true);
        field.set(null, flow.getSnapshot());
    }

    private static int buttons(Component component) {
        int count = component instanceof JButton ? 1 : 0;
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
