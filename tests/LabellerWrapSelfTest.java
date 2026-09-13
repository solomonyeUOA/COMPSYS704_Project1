import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Label evidence/identity regressions and native Swing phase fixtures. */
public final class LabellerWrapSelfTest {
    private static int assertions;

    public static void main(final String[] args) throws Exception {
        testState();
        testIdentityAndReset();
        testAuthoritativeTwinIdentity();
        for (String size : new String[] {"S", "L"}) testGeometry(size);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try { renderPhases(args.length == 0 ? null : new File(args[0])); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        });
        System.out.println("LabellerWrapSelfTest PASS assertions=" + assertions);
    }

    private static void testState() {
        double[] progress = {0, 25, 50, 70, 90};
        for (double p : progress) {
            BottleLabelVisuals.State state = BottleLabelVisuals.resolve(
                7, true, p, false, "--", "--");
            require(state.applied == (p >= 60), "symbolic applied at " + p);
            require(state.floating == (p >= 20 && p < 60), "detached material at " + p);
            require(!state.confirmed, "symbolic evidence never claims live completion");
        }
        require(BottleLabelVisuals.resolve(7, true, 50, false, "", "").coverage > 0,
            "APPLY can demonstrate partial wrapping");
        require(!BottleLabelVisuals.resolve(7, true, 90, true, "P6", "APPLY").applied,
            "local handoff cannot label a live P6 bottle");
        require(!BottleLabelVisuals.resolve(7, true, 100, true, "P6", "FAULT").floating,
            "no floating stock after the application interval");
        require(BottleLabelVisuals.resolve(7, true, 0, true, "LABELLED", "").applied,
            "confirmed LABELLED wins even without local animation");
        require(BottleLabelVisuals.resolve(7, true, 0, true, "P6", "LABEL_VERIFIED").applied,
            "matching real label verification is completion evidence");
        require(!BottleLabelVisuals.resolve(7, false, 100, false, "", "").applied,
            "empty/retired symbolic slot cannot inherit a wrap");
    }

    private static void testIdentityAndReset() {
        ABSLiveTwinModel twin = new ABSLiveTwinModel();
        ABSVisualisationPresentationModel model = new ABSVisualisationPresentationModel();
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        ABSVisualisationTeamIpModel team = new ABSVisualisationTeamIpModel();
        int[] states = new int[10];
        boolean[] received = new boolean[10];
        states[7] = 3;
        received[7] = true;
        require(twin.accept("V2|TWIN|12001|1|W=2|R=1|REJECTED=0|WORKPIECES=" +
            "PO1-B001,LABELLED,LabellerControllerCD,1,S,200;" +
            "PO1-B002,P6,RotaryTableControllerCD,2,L,500|RESOURCES=" +
            "LabellerControllerCD,Labeller,PO1-B001,3,LABEL_VERIFIED,NONE,1"),
            "two-bottle fixture accepted");
        ABSVisualisationPresentationModel.Snapshot snapshot = model.publish(
            flow.getSnapshot(), twin.snapshot(), team.getSnapshot(), states, received);
        for (int module = 7; module <= 9; module++) {
            require(snapshot.getBottleLabel(module, 1, 90).applied,
                "same labelled bottle retains wrap through module " + module);
            require(!snapshot.getBottleLabel(module, 2, 90).applied,
                "prior DONE and bottle 1 evidence cannot label bottle 2");
        }
        require(!snapshot.getBottleLabel(7, 99, 90).applied,
            "mismatched module identity cannot label another active symbol");
        twin.observeReset("RST12001");
        flow.resetSystem();
        model.reset("RST12001");
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(),
            team.getSnapshot(), new int[10], new boolean[10]);
        require(!snapshot.getBottleLabel(7, 0, 0).applied,
            "RESET clears stale applied label projection");

        require(twin.accept("V2|TWIN|12002|1|W=1|R=1|REJECTED=0|WORKPIECES=" +
            "ORDER_A-B001,P6,LabellerControllerCD,1,S,200|RESOURCES=" +
            "LabellerControllerCD,Labeller,ORDER_A-B001,3,LABEL_VERIFIED,NONE,1"),
            "underscore identity fixture accepted");
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(),
            team.getSnapshot(), states, received);
        require(snapshot.getBottleLabel(7, 1, 0).applied,
            "full bottle identity preserves underscores for real verification");
        require(twin.accept("V2|TWIN|12002|2|W=1|R=1|REJECTED=0|WORKPIECES=" +
            "ORDER_A-B002,P6,LabellerControllerCD,2,L,500|RESOURCES=" +
            "LabellerControllerCD,Labeller,ORDER_A-B001,3,LABEL_VERIFIED,NONE,1"),
            "stale resource fixture accepted");
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(),
            team.getSnapshot(), states, received);
        require(!snapshot.getBottleLabel(7, 2, 90).applied,
            "fallback workpiece cannot inherit missing prior bottle's verification");
        require(twin.accept("V2|TWIN|12002|3|W=2|R=0|REJECTED=0|WORKPIECES=" +
            "ORDER_A-B001,LABELLED,LabellerControllerCD,1,S,200;" +
            "ORDER_B-B001,P6,LabellerControllerCD,2,L,500|RESOURCES="),
            "ambiguous sequence fixture accepted");
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(),
            team.getSnapshot(), states, received);
        require(!snapshot.getBottleLabel(7, 1, 90).applied,
            "sequence collision across orders cannot claim a confirmed wrap");
    }

    private static void testAuthoritativeTwinIdentity() {
        ABSLiveTwinModel twin = new ABSLiveTwinModel();
        ABSVisualisationFlowModel flow = new ABSVisualisationFlowModel();
        ABSVisualisationPresentationModel model = new ABSVisualisationPresentationModel();
        ABSVisualisationTeamIpModel team = new ABSVisualisationTeamIpModel();
        String prefix = "V2|TWIN|14001|";
        String rows = "|W=2|R=0|REJECTED=0|WORKPIECES=" +
            "PO0001-P01-B999,COMPLETE,SortPackControllerCD,8,S,200;" +
            "PO0002-P01-B005,P6,LabellerControllerCD,9,L,500|RESOURCES=";
        require(twin.accept(prefix + "1" + rows), "full-key fixture accepted");
        flow.acceptTwinSnapshot(twin.snapshot());
        int displayId = flow.getModuleSnapshot(7).getCurrentBottleId();
        require(displayId != 5, "display ID deliberately differs from bottle sequence");
        ABSVisualisationPresentationModel.Snapshot snapshot = model.publish(
            flow.getSnapshot(), twin.snapshot(), team.getSnapshot(), new int[10], new boolean[10]);
        require("L".equals(snapshot.getBottleSize(7, displayId).getSizeCode()),
            "size follows exact current full Twin key");
        require(!snapshot.getBottleLabel(7, displayId, 100).applied,
            "confirmed P6 cannot become LABELLED from 100 percent schematic position");
        require(twin.accept(prefix + "2" + rows.replace(",P6,", ",LABELLED,")),
            "real label stage accepted");
        flow.acceptTwinSnapshot(twin.snapshot());
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(), team.getSnapshot(),
            new int[10], new boolean[10]);
        require(snapshot.getBottleLabel(7, displayId, 0).applied,
            "full-key LABELLED evidence creates the attached wrap");
        require(twin.accept(prefix + "3" + rows.replace(",P6,LabellerControllerCD,",
            ",UNLOADED,BottleUnloaderControllerCD,")), "unloader stage accepted");
        flow.acceptTwinSnapshot(twin.snapshot());
        snapshot = model.publish(flow.getSnapshot(), twin.snapshot(), team.getSnapshot(),
            new int[10], new boolean[10]);
        require(snapshot.getBottleLabel(8, displayId, 0).applied,
            "same full-key bottle keeps its wrap at Unloader");
        require(!snapshot.getBottleLabel(7, 0, 100).applied,
            "vacated live Labeller cannot retain the previous wrap");
    }

    private static void testGeometry(String size) {
        BottleVisualGeometry geometry = BottleVisualGeometry.forSizeCode(size);
        Rectangle2D band = geometry.labelBounds(285, 370, 70, 125);
        Rectangle2D body = new Rectangle2D.Double(
            285 - geometry.bodyWidth(70) / 2.0,
            geometry.topY(370, 125) + geometry.neckHeight(125),
            geometry.bodyWidth(70), geometry.bodyHeight(125));
        require(body.contains(band), size + " label remains inside the body");
        require(Math.abs(band.getCenterY() - geometry.labelCenterY(370, 125)) < 0.001,
            size + " wrap aligns to the applicator target");
        BufferedImage image = new BufferedImage(500, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { BottleLabelVisuals.drawWrap(g, 285, 370, 70, 125, geometry, 1); }
        finally { g.dispose(); }
        int pixels = 0;
        for (int y = 0; y < 500; y++) for (int x = 0; x < 500; x++) {
            if ((image.getRGB(x, y) >>> 24) != 0) {
                pixels++;
                require(body.contains(x + 0.5, y + 0.5), size + " painted label is clipped");
            }
        }
        require(pixels > 100, size + " visible wrap band");
        System.out.println(size + "_LABEL_INSIDE_BOTTLE_BODY = PASS");
        System.out.println(size + "_LABEL_CENTER_VALID = PASS");
    }

    private static void renderPhases(File output) throws Exception {
        ABSVisualisation.resetSystem("RST18000");
        double[] phases = {0, 25, 50, 70, 90};
        for (String size : new String[] {"S", "L"}) {
            for (double p : phases) {
                ABSVisualisation.ModuleDetailPanel panel =
                    new ABSVisualisation.ModuleDetailPanel(7);
                field(panel, "presentationSize").set(panel,
                    new ABSVisualisationPresentationModel.BottleSizeContext(
                        size, "S".equals(size) ? "200" : "500", false,
                        "CAPABILITY / SYMBOLIC TEST FIXTURE"));
                field(panel, "detailModel").set(panel,
                    new ABSVisualisationFlowModel.ModuleSnapshot(
                        1, 7, p, 0, 0, 0, 0, 0, new int[6], 0, 0,
                        60, 40, 0, "", "SYMBOLIC",
                        ABSVisualisationFlowModel.ModuleLifecycle.ACTIVE,
                        1, true, ""));
                BottleLabelVisuals.State state = panel.getLabelStateForTest();
                require(state.applied == (p >= 60), "detail uses shared label state");
                JPanel canvas = (JPanel)field(panel, "detailCanvas").get(panel);
                canvas.setSize(500, 500);
                BufferedImage image = new BufferedImage(500, 530, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.setColor(new Color(255, 236, 165));
                    g.fillRect(0, 0, 500, 530);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    g.drawString("SYMBOLIC TEST FIXTURE | " + size + " | " + state.phase, 14, 20);
                    g.translate(0, 30);
                    canvas.printAll(g);
                }
                finally { g.dispose(); panel.stopAnimation(); }
                if (output != null) {
                    if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException();
                    File target = new File(output, size + "-" + state.phase.replace(' ', '-') + ".png");
                    require(ImageIO.write(image, "png", target), "PNG written");
                }
            }
        }
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
