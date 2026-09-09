/** The visualization must display only actual, ordered twin evidence. */
public final class LiveTwinVisualisationSelfTest {
    public static void main(String[] args) {
        ABSLiveTwinModel model = new ABSLiveTwinModel();
        require(model.snapshot() == null, "no fabricated startup bottles");
        String sample = "V2|TWIN|0|1|W=1|R=1|REJECTED=0|WORKPIECES=B%2C1,COMPLETE,COLLECTION,10,L,500|RESOURCES=LABELLER-1,LABELLER,B%2C1,3,LABEL_VERIFIED,-,2";
        require(model.accept(sample), "accept complete snapshot");
        require("B,1".equals(model.snapshot().workpieces()[0][0]), "escaped identifier decoded");
        require(!model.accept(sample), "duplicate does not repaint or mutate");
        String[][] copy = model.snapshot().workpieces();
        copy[0][0] = "CORRUPTED";
        require("B,1".equals(model.snapshot().workpieces()[0][0]), "immutable view");
        require(!model.accept(sample.replace("|0|1|", "|0|2|").replace("W=1", "W=2")), "counts validated");
        model.observeReset("RST0001");
        require(model.snapshot() == null, "reset clears display");
        require(!model.accept(sample.replace("|0|1|", "|0|99|")), "late old-generation snapshot rejected");
        require(model.accept("V2|TWIN|2|2|W=0|R=0|REJECTED=0|WORKPIECES=|RESOURCES="), "new empty snapshot accepted");
        model.observeReset("RST0000");
        require(model.snapshot() != null, "stale reset cannot clear new display");
        require(!model.accept("V2|TWIN|2|3|W=1|R=0|REJECTED=0|WORKPIECES=B,CREATED,L,1,L,200|RESOURCES="), "invalid profile rejected");
        ABSLiveTwinModel zeroReset = new ABSLiveTwinModel();
        require(zeroReset.accept(sample), "startup evidence accepted");
        zeroReset.observeReset("RST0000");
        require(zeroReset.snapshot() == null && !zeroReset.accept(sample), "RST0000 is distinct from startup");
        System.out.println("LiveTwinVisualisationSelfTest PASSED");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
