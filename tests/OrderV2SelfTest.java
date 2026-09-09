/** Framework-free validation checks for the size-aware ORDER V2 contract. */
public final class OrderV2SelfTest {
    private OrderV2SelfTest() {
    }

    public static void main(String[] args) {
        OrderV2 small = OrderV2.parse("PO0001|1|P1,S,60,40,3");
        require(small != null, "valid S order must parse");
        require("P1".equals(small.productIdAt(0)), "S product ID");
        require("S".equals(small.sizeCodeAt(0)), "S size code");
        require(small.capacityMlAt(0) == 200, "S capacity is 200 mL");
        require(small.quantityAt(0) == 3, "S quantity");

        OrderV2 mixed = OrderV2.parse(
            "PO0002|2|P1,S,60,40,3;P2,L,50,50,2"
        );
        require(mixed != null && mixed.productCount == 2,
            "mixed two-product order must parse");
        require("L".equals(mixed.sizeCodeAt(1)), "L size code");
        require(mixed.capacityMlAt(1) == 500, "L capacity is 500 mL");
        require(mixed.liquidARatioAt(1) == 50 &&
            mixed.liquidBRatioAt(1) == 50, "mixed recipe retained");

        require(OrderV2.parse("PO|1|P1,s,60,40,1") == null,
            "lower-case size must fail");
        require(OrderV2.parse("PO|1|P1,M,60,40,1") == null,
            "unsupported size must fail");
        require(OrderV2.parse("PO|1|P1,S,60,30,1") == null,
            "ratios not totalling 100 must fail");
        require(OrderV2.parse("PO|1|P1,L,60,40,0") == null,
            "zero quantity must fail");
        require(OrderV2.parse("PO|2|P1,S,60,40,1") == null,
            "product count mismatch must fail");
        require(OrderV2.parse(
            "PO|5|P1,S,50,50,1;P2,S,50,50,1;" +
            "P3,S,50,50,1;P4,S,50,50,1;P5,S,50,50,1"
        ) == null, "V1-compatible maximum product count must be enforced");

        System.out.println("OrderV2SelfTest PASSED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
