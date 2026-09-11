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
        CoordinatorStateV1.completionSendAfterMillis = 0L;
        String completion = CoordinatorStateV1.nextCompletionTransmission();
        require(
            completion != null &&
            completion.startsWith("PO-MULTI|COMPLETED|"),
            "completion payload must follow V1"
        );
        require(
            CoordinatorStateV1.completionTransmissionStarted,
            "completion transport window must report its first reaction"
        );
        int remainingAfterStart =
            CoordinatorStateV1.completionTransmissionsRemaining;
        require(
            completion.equals(
                CoordinatorStateV1.nextCompletionTransmission()
            ) &&
            CoordinatorStateV1.completionTransmissionsRemaining ==
                remainingAfterStart,
            "one completion copy must remain PRESENT without consuming " +
            "another attempt"
        );
        require(
            CoordinatorStateV1.isDuplicateOfLastAcceptedOrder(
                "PO-MULTI|2|P1,60,40,2;P2,25,75,1"
            ),
            "a held/late copy of the accepted order must be recognised"
        );
        require(
            !CoordinatorStateV1.accept(
                "PO-MULTI|2|P1,60,40,2;P2,25,75,1"
            ),
            "a completed order ID must not be restarted by a late copy"
        );

        require(
            CoordinatorStateV1.consumeBottleDoneEdge(true),
            "first PRESENT reaction must create one BOTTLE_DONE edge"
        );
        require(
            !CoordinatorStateV1.consumeBottleDoneEdge(true),
            "held BOTTLE_DONE must not be counted twice"
        );
        require(
            !CoordinatorStateV1.consumeBottleDoneEdge(false),
            "ABSENT reaction must only reset the BOTTLE_DONE latch"
        );
        require(
            CoordinatorStateV1.consumeBottleDoneEdge(true),
            "a later BOTTLE_DONE after ABSENT must create a new edge"
        );
        CoordinatorStateV1.consumeBottleDoneEdge(false);

        require(
            CoordinatorStateV1.recordFtFaultAlert("opaque-alert"),
            "FT alert must be recorded"
        );
        require(
            !CoordinatorStateV1.ftCoordinationHold,
            "FT alert alone must not hold production"
        );
        require(
            CoordinatorStateV1.recordFtSafeStopRequest("opaque-stop"),
            "safe-stop request must be recorded"
        );
        require(
            CoordinatorStateV1.ftCoordinationHold,
            "safe-stop request must hold new order/batch dispatch"
        );
        require(
            !CoordinatorStateV1.canSendFtSafeStopAck(),
            "ACK must remain blocked without independent safe-stop evidence"
        );
        require(
            CoordinatorStateV1.recordFtRecoveryReady("opaque-ready"),
            "recovery-ready must be recorded"
        );
        require(
            CoordinatorStateV1.ftCoordinationHold,
            "recovery-ready must not automatically resume production"
        );
        require(
            CoordinatorStateV1.recordFtRecoveryFailed("opaque-failed"),
            "recovery-failed must be recorded"
        );
        require(
            CoordinatorStateV1.ftCoordinationHold,
            "recovery-failed must retain HOLD"
        );
        require(
            !CoordinatorStateV1.accept("PO-HELD|1|P1,60,40,1"),
            "Coordinator must reject a new order while FT HOLD is active"
        );

        System.out.println("OrderV1SelfTest PASSED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
