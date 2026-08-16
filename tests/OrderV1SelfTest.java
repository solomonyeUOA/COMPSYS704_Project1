/** Framework-free checks for the frozen ORDER V1 parser and batch state. */
public final class OrderV1SelfTest {
    private OrderV1SelfTest() {
    }

    public static void main(String[] args) {
        OrderV1 order = OrderV1.parse(
            "PO-MULTI|2|P1,60,40,2;P2,25,75,1"
        );
        require(order != null, "valid two-product order must parse");
        require(order.productCount == 2, "productCount must be 2");
        require(order.quantities[0] == 2, "first quantity must be 2");
        require(order.liquidARatios[1] == 25, "second A ratio must be 25");

        require(OrderV1.parse("PO|0|") == null, "zero products must fail");
        require(
            OrderV1.parse("PO|1|P1,60,30,1") == null,
            "ratios not totalling 100 must fail"
        );
        require(
            OrderV1.parse("PO|1|P1,60,40,0") == null,
            "zero quantity must fail"
        );
        require(
            OrderV1.parse("PO|2|P1,60,40,1") == null,
            "product count mismatch must fail"
        );

        require(
            CoordinatorStateV1.accept(
                "PO-MULTI|2|P1,60,40,2;P2,25,75,1"
            ),
            "Coordinator must accept the valid order"
        );
        require(
            CoordinatorStateV1.requiredBottles == 2,
            "first batch quantity must be loaded"
        );
        require(!CoordinatorStateV1.recordBottleDone(), "first bottle only");
        require(CoordinatorStateV1.recordBottleDone(), "first batch complete");
        require(CoordinatorStateV1.hasNextProduct(), "second product exists");
        CoordinatorStateV1.advanceToNextProduct();
        require(
            CoordinatorStateV1.requiredBottles == 1 &&
            CoordinatorStateV1.currentLiquidARatio == 25 &&
            CoordinatorStateV1.currentLiquidBRatio == 75,
            "second batch values must be loaded"
        );
        require(
            CoordinatorStateV1.recordBottleDone(),
            "second batch must complete after one bottle"
        );
        CoordinatorStateV1.completeOrder();
        String completion = CoordinatorStateV1.nextCompletionTransmission();
        require(
            completion.startsWith("PO-MULTI|COMPLETED|"),
            "completion payload must follow V1"
        );

        System.out.println("OrderV1SelfTest PASSED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
