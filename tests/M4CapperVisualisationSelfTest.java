import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Official M4 payload acceptance, precedence, fault hold and reset regression. */
public final class M4CapperVisualisationSelfTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        M4CapperPresentationModel model = new M4CapperPresentationModel();
        require(model.accept("V1|M4-B001|L|GEOM_L|TWISTING|2"), "official payload");
        M4CapperPresentationModel.Snapshot twisting = model.snapshot();
        require(model.accept(twisting.payload) && model.snapshot() == twisting,
            "duplicates are idempotent");
        for (String invalid : new String[] {null, "", "V2|B1|L|GEOM_L|DONE|3",
            "V1|B1|S|GEOM_L|LOWERING|2", "V1|-|S|GEOM_S|WAITING|1",
            "V1|B1|-|-|WAITING|1", "V1| B1|S|GEOM_S|DONE|3",
            "V1|B1|S|GEOM_S|UNKNOWN|2", "V1|B1|S|GEOM_S|DONE|5",
            "V1|B1|S|GEOM_S|DONE|three", "V1|B1|S|GEOM_S|DONE|3|extra"}) {
            require(!model.accept(invalid) && model.snapshot() == twisting,
                "invalid payload cannot erase live evidence");
        }
        require(model.accept("V1|M4-B001|L|GEOM_L|FAULT|4") &&
            model.snapshot().displayPosition == 52, "fault freezes this bottle's last arm pose");
        require(model.accept("V1|M4-B002|S|GEOM_S|FAULT|4") &&
            model.snapshot().displayPosition == 0, "new bottle cannot inherit old arm pose");
        require(model.accept("V1|-|-|-|WAITING|1") && !model.snapshot().hasBottle(),
            "official idle profile");
        model.reset();
        require(model.snapshot() == null, "model reset clears telemetry");
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ABSVisualisation.resetSystem("RST19000");
                ABSVisualisation.ModuleDetailPanel panel = new ABSVisualisation.ModuleDetailPanel(6);
                require(ABSVisualisation.updateM4CapperState(
                    "V1|LIVE-L-B001|L|GEOM_L|LOWERING|2"), "consumer accepts live telemetry");
                panel.syncRealState();
                require(panel.getDisplayedRealStatus() == 2, "direct M4 status displayed");
                require(panel.getBottleScaleForTest() == 1.18, "M4 geometry drives bottle size");
                require(text(panel).contains("LIVE-L-B001") && text(panel).contains("GEOM_L") &&
                    text(panel).contains("LOWERING") &&
                    text(panel).contains("GRIP_Z_L") &&
                    text(panel).contains("CLAMP_WIDE") &&
                    text(panel).contains("CONFIRMED"),
                    "real telemetry displays identity, actuator targets and position evidence");
                ABSVisualisation.updateStatus("Capper", 3);
                panel.syncRealState();
                require(panel.getDisplayedRealStatus() == 2 && panel.getCapperPositionForTest() == 28,
                    "aggregate status/local animation cannot override direct arm state");
                ABSVisualisation.updateM4CapperState("V1|LIVE-L-B001|L|GEOM_L|FAULT|4");
                panel.syncRealState();
                require(panel.getCapperPositionForTest() == 28, "real fault retains pose");
                ABSVisualisation.resetSystem("RST19001");
                panel.syncRealState();
                require(!text(panel).contains("LIVE-L-B001") && panel.getCapperPositionForTest() == 0,
                    "reset clears live identity and pose");
                panel.stopAnimation();
            }
        });
        System.out.println("M4CapperVisualisationSelfTest PASS assertions=" + assertions);
    }

    private static String text(Component c) {
        String value = c instanceof JLabel ? ((JLabel)c).getText() : "";
        if (c instanceof Container) for (Component child : ((Container)c).getComponents()) value += text(child);
        return value;
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
