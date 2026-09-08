/** Deterministic M1 batch identity and bounded retry checks. */
public final class M1M4BatchSyncSelfTest {
    private M1M4BatchSyncSelfTest() {
    }

    public static void main(String[] args) {
        caseARetryKeepsIdenticalBatchId();
        caseBNextProductCreatesNewBatchId();
        caseCSamePayloadDoesNotMutateIdentity();
        System.out.println(
            "M1M4BatchSyncSelfTest PASSED (M1 batch cases A-C)"
        );
    }

    private static void caseARetryKeepsIdenticalBatchId() {
        M1SimulationBatchOfferV1 offer =
            new M1SimulationBatchOfferV1(3, 600L);
        require(offer.beginProductBatch("PO0001", 1, 20, 0L),
            "M1-A initial product batch");
        String expected = "PO0001-P01|20";
        require(expected.equals(offer.nextReactionValue(0L)),
            "M1-A first payload");
        require(offer.nextReactionValue(1L) == null,
            "M1-A ABSENT reaction after first pulse");
        require(expected.equals(offer.nextReactionValue(600L)),
            "M1-A second payload is identical");
        require(offer.nextReactionValue(601L) == null,
            "M1-A ABSENT reaction after second pulse");
        require(expected.equals(offer.nextReactionValue(1200L)),
            "M1-A third payload is identical");
        require(offer.nextReactionValue(1201L) == null &&
            !offer.isPending(), "M1-A bounded retry drains after 3 pulses");
    }

    private static void caseBNextProductCreatesNewBatchId() {
        require(CoordinatorStateV1.accept(
            "PO0100|2|APPLE,60,40,1;BERRY,25,75,3"
        ), "M1-B multi-product order accepted");
        require("PO0100-P01".equals(
            CoordinatorStateV1.currentM4SimulationBatchId()),
            "M1-B first product batch ID");
        require("PO0100-P01|1".equals(
            CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "M1-B first product payload");
        require(CoordinatorStateV1.recordBottleDone(),
            "M1-B first product completes");
        CoordinatorStateV1.advanceToNextProduct();
        require("PO0100-P02".equals(
            CoordinatorStateV1.currentM4SimulationBatchId()),
            "M1-B next product gets a new ID");
        require("PO0100-P02|3".equals(
            CoordinatorStateV1.currentM4SimulationBatchPayload()),
            "M1-B next product quantity follows POS order");

        CoordinatorStateV1.nextStatusPollMillis = Long.MAX_VALUE;
        Coordinator coordinator = new Coordinator("M1M4BatchProbe");
        coordinator.init();
        coordinator.M4_SIM_BATCH_REQUEST.setClear();
        coordinator.runClockDomain();
        require(coordinator.M4_SIM_BATCH_REQUEST.getStatus(),
            "M1-B generated Coordinator emits simulation request");
        require("PO0100-P02|3".equals(
            coordinator.M4_SIM_BATCH_REQUEST.getValue()),
            "M1-B generated Coordinator carries stable payload");
    }

    private static void caseCSamePayloadDoesNotMutateIdentity() {
        M1SimulationBatchOfferV1 offer =
            new M1SimulationBatchOfferV1(3, 600L);
        require(offer.beginProductBatch("PO0200", 1, 10, 0L),
            "M1-C batch accepted");
        String stable = offer.getStablePayload();
        require(stable.equals(offer.nextReactionValue(0L)),
            "M1-C first offer");
        require(offer.beginProductBatch("PO0200", 1, 10, 50L),
            "M1-C duplicate begin is idempotent");
        require(stable.equals(offer.getStablePayload()) &&
            offer.getOfferCount() == 1,
            "M1-C duplicate does not reset payload or retry count");
        require(!offer.beginProductBatch("PO0200", 1, 5, 60L),
            "M1-C conflicting quantity rejected");
        require(stable.equals(offer.getStablePayload()) &&
            offer.getOfferCount() == 1,
            "M1-C conflict does not mutate identity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
