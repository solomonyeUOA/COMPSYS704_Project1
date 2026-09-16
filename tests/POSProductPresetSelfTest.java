import javax.swing.SwingUtilities;

/** Focused checks for independent POS preset/custom product rows. */
public final class POSProductPresetSelfTest {
    private POSProductPresetSelfTest() { }

    public static void main(String[] args) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    runCases();
                }
                catch (Throwable error) {
                    failure[0] = error;
                }
            }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception) {
                throw (Exception)failure[0];
            }
            if (failure[0] instanceof Error) {
                throw (Error)failure[0];
            }
            throw new RuntimeException(failure[0]);
        }
        System.out.println("POSProductPresetSelfTest PASSED");
    }

    private static void runCases() {
        POSVisualisation.ProductInputRow row = row("P1", "3", "25", "75");
        require(row.selectablePresetCountForTest() == 4,
            "POS exposes three presets and a Custom recipe");
        assertPreset(row, POSVisualisation.ProductPreset.P1, "P1", "25.0", "75.0");
        require("PO-P1|1|P1,S,25.0,75.0,3".equals(
            POSVisualisation.buildOrderPayloadForTest("PO-P1", row)
        ), "P1 preset encoding");

        row.selectPresetForTest(POSVisualisation.ProductPreset.P2);
        row.setSizeAndQuantityForTest(OrderV2.LARGE, "2");
        assertPreset(row, POSVisualisation.ProductPreset.P2, "P2", "50.0", "50.0");
        require("PO-P2|1|P2,L,50.0,50.0,2".equals(
            POSVisualisation.buildOrderPayloadForTest("PO-P2", row)
        ), "P1 -> P2 and P2 encoding");

        row.selectPresetForTest(POSVisualisation.ProductPreset.P3);
        row.setSizeAndQuantityForTest(OrderV2.SMALL, "5");
        assertPreset(row, POSVisualisation.ProductPreset.P3, "P3", "75.0", "25.0");
        require("PO-P3|1|P3,S,75.0,25.0,5".equals(
            POSVisualisation.buildOrderPayloadForTest("PO-P3", row)
        ), "P2 -> P3 and P3 encoding");

        row.selectPresetForTest(POSVisualisation.ProductPreset.CUSTOM);
        require(row.customFieldsEditableForTest(),
            "preset -> Custom unlocks ID and ratios");
        row.setCustomValuesForTest("JUICE_X", "40.5", "59.5");
        row.setSizeAndQuantityForTest(OrderV2.LARGE, "2");
        require("PO-C|1|JUICE_X,L,40.5,59.5,2".equals(
            POSVisualisation.buildOrderPayloadForTest("PO-C", row)
        ), "custom name, ratios, size and quantity preserved");

        row.selectPresetForTest(POSVisualisation.ProductPreset.P1);
        assertPreset(row, POSVisualisation.ProductPreset.P1, "P1", "25.0", "75.0");
        row.selectPresetForTest(POSVisualisation.ProductPreset.CUSTOM);
        require("JUICE_X".equals(row.productIdForTest()) &&
            "40.5".equals(row.liquidAForTest()) &&
            "59.5".equals(row.liquidBForTest()),
            "Custom draft remains row-local after preset display");

        row.setCustomValuesForTest("BAD", "40.5", "50.0");
        expectInvalid(row, "Custom A+B validation");

        POSVisualisation.ProductInputRow p1 = row("P1", "2", "25", "75");
        POSVisualisation.ProductInputRow p2 = row("P2", "1", "50", "50");
        p2.setSizeAndQuantityForTest(OrderV2.LARGE, "1");
        POSVisualisation.ProductInputRow custom = row(
            "MYPRODUCT", "3", "30", "70"
        );
        String mixed = POSVisualisation.buildOrderPayloadForTest(
            "PO-MIXED",
            p1,
            p2,
            custom
        );
        require(("PO-MIXED|3|P1,S,25.0,75.0,2;" +
            "P2,L,50.0,50.0,1;MYPRODUCT,S,30.0,70.0,3").equals(mixed),
            "independent preset/custom rows encode in order");
        require("P1".equals(p1.productIdForTest()) &&
            "P2".equals(p2.productIdForTest()) &&
            "MYPRODUCT".equals(custom.productIdForTest()),
            "one row does not mutate another");
        require(OrderV2.parse(mixed) != null, "mixed order remains ORDER V2");
        require(OrderV2.parse("PO-OLD|1|P1,S,60,40,1") != null,
            "existing explicit-ratio ORDER V2 remains compatible");

        expectTooManyRows(p1, p2, custom);
    }

    private static POSVisualisation.ProductInputRow row(
        String id,
        String quantity,
        String liquidA,
        String liquidB
    ) {
        return new POSVisualisation.ProductInputRow(
            id,
            quantity,
            liquidA,
            liquidB
        );
    }

    private static void assertPreset(
        POSVisualisation.ProductInputRow row,
        POSVisualisation.ProductPreset preset,
        String id,
        String liquidA,
        String liquidB
    ) {
        row.selectPresetForTest(preset);
        require(id.equals(row.productIdForTest()), preset + " product ID");
        require(liquidA.equals(row.liquidAForTest()), preset + " liquid A");
        require(liquidB.equals(row.liquidBForTest()), preset + " liquid B");
        require(!row.customFieldsEditableForTest(),
            preset + " fields are derived and locked");
    }

    private static void expectInvalid(
        POSVisualisation.ProductInputRow row,
        String message
    ) {
        try {
            POSVisualisation.buildOrderPayloadForTest("PO-BAD", row);
            throw new AssertionError(message + " was accepted");
        }
        catch (IllegalArgumentException expected) {
            require(expected.getMessage().indexOf("equal 100") >= 0, message);
        }
    }

    private static void expectTooManyRows(
        POSVisualisation.ProductInputRow p1,
        POSVisualisation.ProductInputRow p2,
        POSVisualisation.ProductInputRow custom
    ) {
        try {
            POSVisualisation.buildOrderPayloadForTest(
                "PO-TOO-MANY",
                p1,
                p2,
                custom,
                row("P3", "1", "75", "25"),
                row("P1", "1", "25", "75")
            );
            throw new AssertionError("maximum product count was not enforced");
        }
        catch (IllegalArgumentException expected) {
            require(expected.getMessage().indexOf("1 to 4") >= 0,
                "maximum product rule reports a useful error");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
